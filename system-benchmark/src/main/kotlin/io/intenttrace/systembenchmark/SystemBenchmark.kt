package io.intenttrace.systembenchmark

import io.intenttrace.detector.ContextRead
import io.intenttrace.detector.EventTimeMonitor
import io.intenttrace.detector.FreshnessCheck
import io.intenttrace.detector.IntentTraceDetector
import io.intenttrace.detector.JsonLinesFindingSink
import io.intenttrace.detector.LatestVersionOracle
import io.intenttrace.detector.ReplayGuard
import io.intenttrace.detector.VersionStamp
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import redis.clients.jedis.Jedis
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.PriorityQueue
import java.util.Properties
import java.util.SplittableRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** Samples one thread contributes while the measurement window is open. */
class ThreadSamples(val role: String) {
    val latency = LongList()
    val service = LongList()
    val oracle = LongList()
    val propagation = LongList()
    val checkDelay = LongList()
    var operations = 0L
}

/** Shared run state: the clock origin, the measurement window and the samples. */
class RunState(val startNanos: Long) {
    @Volatile var measuring = false
    @Volatile var producerDone = false
    val samples = CopyOnWriteArrayList<ThreadSamples>()
    private val local = ThreadLocal<ThreadSamples>()
    val sendFailures = AtomicLong()

    /** This thread's samples, registered under [role] on first use. */
    fun mine(role: String = "decision"): ThreadSamples =
        local.get() ?: ThreadSamples(role).also { local.set(it); samples += it }

    fun ofRole(role: String): List<ThreadSamples> = samples.filter { it.role == role }

    fun waitUntil(offsetNanos: Long) {
        val target = startNanos + offsetNanos
        while (true) {
            val remaining = target - System.nanoTime()
            if (remaining <= 0) return
            if (remaining > 200_000) LockSupport.parkNanos(remaining - 100_000) else Thread.onSpinWait()
        }
    }
}

/**
 * The primary store's change log in Redis: one sorted set per user, scored by
 * commit time. Asking for the highest score at or before a decision gives the
 * latest eligible version at that boundary, independently of the cache.
 */
class RedisVersionOracle(
    private val connection: ThreadLocal<Jedis>,
    private val state: RunState
) : LatestVersionOracle {
    override fun latestEligible(contextKey: String, decidedAt: java.time.Instant): VersionStamp? {
        val began = System.nanoTime()
        try {
            val latest = connection.get().zrevrangeByScoreWithScores(
                "primary:$contextKey", Micros.of(decidedAt).toDouble(), Double.NEGATIVE_INFINITY, 0, 1
            ).firstOrNull() ?: return null
            return VersionStamp(latest.element.toLong(), Micros.toInstant(latest.score.toLong()))
        } finally {
            if (state.measuring) state.mine().oracle.add(System.nanoTime() - began)
        }
    }
}

private const val KEPT_PRIMARY_VERSIONS = 64

/** Commits events to the primary, then publishes them to Kafka, with the scheduled send faults. */
class PrimaryProducer(
    private val config: BenchmarkConfig,
    private val workload: Workload,
    private val redis: Jedis,
    private val producer: KafkaProducer<String, String>,
    private val commits: JsonlLog,
    private val state: RunState,
    private val clock: Clock
) {
    private class Send(val atNanos: Long, val sequence: Long, val event: ContextEvent)

    private val versions = LongArray(config.users) { 1L }
    private val payloadRandom = SplittableRandom(config.seed * 31 + 3)

    fun run() {
        val sends = PriorityQueue<Send>(compareBy<Send>({ it.atNanos }, { it.sequence }))
        var sequence = 0L
        var next = 0
        val events = workload.events
        while (next < events.size || sends.isNotEmpty()) {
            val commitAt = if (next < events.size) events[next].atNanos else Long.MAX_VALUE
            val sendAt = sends.peek()?.atNanos ?: Long.MAX_VALUE
            if (sendAt <= commitAt) {
                val send = sends.poll()
                state.waitUntil(send.atNanos)
                publish(send.event)
                continue
            }
            val scheduled = events[next++]
            state.waitUntil(scheduled.atNanos)
            val event = commit(scheduled)
            val now = System.nanoTime() - state.startNanos
            fun later(millis: Long) = now + millis * 1_000_000
            when (scheduled.fault) {
                SendFault.NONE -> publish(event)
                SendFault.DELAYED -> sends += Send(later(config.delayedSendMillis), sequence++, event)
                SendFault.DUPLICATE -> {
                    publish(event)
                    sends += Send(later(config.duplicateAfterMillis), sequence++, event)
                }
                SendFault.CONFLICTING -> {
                    publish(event)
                    sends += Send(later(config.conflictingAfterMillis), sequence++, event.copy(payload = event.payload + ".conflict"))
                }
            }
        }
    }

    private fun commit(scheduled: ScheduledEvent): ContextEvent {
        val user = scheduled.user
        val version = ++versions[user]
        val payload = "p$user.$version.${java.lang.Long.toHexString(payloadRandom.nextLong())}"
        val committed = Micros.of(clock.instant())
        redis.zadd(primaryKey(user), committed.toDouble(), version.toString())
        if (version % 16 == 0L) redis.zremrangeByRank(primaryKey(user), 0, -(KEPT_PRIMARY_VERSIONS + 1L))
        val event = ContextEvent("e${scheduled.index}", user, version, committed, payload)
        commits.write(
            "eventId" to event.eventId, "user" to user, "version" to version,
            "committedMicros" to committed, "payload" to payload, "fault" to scheduled.fault.id
        )
        return event
    }

    private fun publish(event: ContextEvent) {
        producer.send(ProducerRecord(config.topic, contextKey(event.user), event.encode())) { _, error ->
            if (error != null) state.sendFailures.incrementAndGet()
        }
    }
}

/**
 * Applies events from Kafka to the Redis cache in arrival order, the way a
 * simple consumer does. With the detector on, it follows the replay guard's
 * verdict and reports arrivals and applications to the event-time monitor.
 */
class CacheConsumer(
    private val config: BenchmarkConfig,
    private val consumer: KafkaConsumer<String, String>,
    private val partitions: List<TopicPartition>,
    private val redis: Jedis,
    private val detector: IntentTraceDetector?,
    private val arrivals: JsonlLog,
    private val applies: JsonlLog,
    private val state: RunState,
    private val clock: Clock
) {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun run() {
        val samples = state.mine("consumer")
        while (true) {
            val records = consumer.poll(Duration.ofMillis(20))
            if (records.isEmpty) {
                if (state.producerDone && caughtUp()) return
                continue
            }
            val toWrite = ArrayList<ContextEvent>(records.count())
            for (record in records) {
                val ingestedAt = clock.instant()
                val event = ContextEvent.decode(record.value())
                arrivals.write(
                    "eventId" to event.eventId, "user" to event.user, "version" to event.version,
                    "occurredMicros" to event.occurredMicros, "ingestedMicros" to Micros.of(ingestedAt),
                    "ingestedNanos" to Micros.nanosOf(ingestedAt),
                    "payload" to event.payload
                )
                var apply = true
                if (detector != null) {
                    val key = contextKey(event.user)
                    val delivery = detector.onDelivery(key, event.eventId, sha256Hex(digest, event.payload), ingestedAt)
                    detector.onArrival(key, event.eventId, Micros.toInstant(event.occurredMicros), ingestedAt)
                    apply = delivery.shouldApply
                }
                if (apply && !isLostWrite(event.eventId, config.lostWritePerMille)) toWrite += event
                if (state.measuring) samples.operations++
            }
            if (toWrite.isEmpty()) continue
            val pipeline = redis.pipelined()
            for (event in toWrite) {
                pipeline.hset(
                    cacheKey(event.user), mapOf(
                        "v" to event.version.toString(), "c" to event.occurredMicros.toString(),
                        "o" to event.occurredMicros.toString(), "p" to event.payload
                    )
                )
            }
            pipeline.sync()
            val appliedMicros = Micros.of(clock.instant())
            for (event in toWrite) {
                applies.write(
                    "eventId" to event.eventId, "user" to event.user, "version" to event.version,
                    "occurredMicros" to event.occurredMicros, "appliedMicros" to appliedMicros
                )
                if (state.measuring) samples.propagation.add((appliedMicros - event.occurredMicros) * 1_000)
                detector?.onApplied(contextKey(event.user), event.eventId, Micros.toInstant(event.occurredMicros))
            }
        }
    }

    private fun caughtUp(): Boolean {
        val ends = consumer.endOffsets(partitions)
        return partitions.all { consumer.position(it) >= (ends[it] ?: 0L) }
    }
}

/**
 * Serves decisions: reads the user's context from the cache and, with the
 * detector on, checks the read against the primary's change log.
 */
class DecisionWorker(
    private val worker: Int,
    private val config: BenchmarkConfig,
    private val workload: Workload,
    private val connection: ThreadLocal<Jedis>,
    private val detector: IntentTraceDetector?,
    private val decisions: JsonlLog,
    private val state: RunState,
    private val clock: Clock,
    private val saturationCount: AtomicLong,
    private val checker: BackgroundChecker?
) {
    private var sink = 0L

    fun run() {
        val samples = state.mine()
        val scheduled = workload.decisions
        for (i in worker until scheduled.size step config.decisionThreads) {
            val decision = scheduled[i]
            state.waitUntil(decision.atNanos)
            val began = System.nanoTime()
            decide("d${decision.index}", decision.user, "fixed")
            val ended = System.nanoTime()
            if (state.measuring) {
                // Latency counts from the scheduled time, so a backlog is not hidden (no coordinated omission).
                samples.latency.add(ended - (state.startNanos + decision.atNanos))
                samples.service.add(ended - began)
                samples.operations++
            }
        }
        state.waitUntil(config.fixedRateSeconds * 1_000_000_000L)
        val until = state.startNanos + config.totalSeconds * 1_000_000_000L
        val zipf = ZipfSampler(config.users, config.zipfExponent)
        val random = SplittableRandom(config.seed * 131 + worker)
        var n = 0L
        while (System.nanoTime() < until) {
            decide("s$worker.${n++}", zipf.sample(random), "saturation")
        }
        saturationCount.addAndGet(n)
        if (sink == Long.MIN_VALUE) println(sink)
    }

    private fun decide(id: String, user: Int, phase: String) {
        val read = connection.get().hgetAll(cacheKey(user))
        val decidedAt = clock.instant()
        val version = read["v"]?.toLong()
        val committed = read["c"]?.toLong()
        val occurred = read["o"]?.toLong()
        sink += (read["p"]?.hashCode() ?: 0).toLong()
        if (detector != null) {
            val used = if (version != null && committed != null) VersionStamp(version, Micros.toInstant(committed)) else null
            val contextRead = ContextRead(id, contextKey(user), decidedAt, used)
            val reflected = occurred?.let(Micros::toInstant)
            if (checker != null) checker.submit(contextRead, reflected) else detector.checkDecision(contextRead, reflected)
        }
        decisions.write(
            "decisionId" to id, "user" to user, "decidedMicros" to Micros.of(decidedAt),
            "decidedNanos" to Micros.nanosOf(decidedAt),
            "version" to version, "occurredMicros" to occurred, "phase" to phase
        )
    }
}

/**
 * Runs decision checks off the decision path. The oracle answers for the
 * decision's own timestamp from the primary's change log, so a later check
 * still judges the read as of the decision. When the queue is full the check
 * is dropped and counted, so the decision path never waits.
 */
class BackgroundChecker(private val detector: IntentTraceDetector, config: BenchmarkConfig, private val state: RunState) {
    val threads = CopyOnWriteArrayList<Thread>()
    private val dropped = AtomicLong()
    private val executor = ThreadPoolExecutor(
        config.checkerThreads, config.checkerThreads, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(config.checkerQueue),
        { task -> Thread({ state.mine("checker"); task.run() }, "decision-checker-${threads.size}").also { threads += it } },
        { _, _ -> dropped.incrementAndGet() }
    ).apply { prestartAllCoreThreads() }

    fun submit(read: ContextRead, reflectedOccurredAt: java.time.Instant?) {
        val submitted = System.nanoTime()
        executor.execute {
            state.mine("checker").checkDelay.add(System.nanoTime() - submitted)
            detector.checkDecision(read, reflectedOccurredAt)
        }
    }

    val droppedChecks: Long get() = dropped.get()

    fun drain() {
        executor.shutdown()
        check(executor.awaitTermination(5, TimeUnit.MINUTES)) { "Background checks did not finish" }
    }
}

/** Runs one benchmark configuration end to end and writes its logs and metrics. */
class SystemBenchmark(private val config: BenchmarkConfig) {
    private val clock = Clock.systemUTC()

    fun run() {
        val out = config.output
        Files.createDirectories(out)
        Files.writeString(out.resolve("config.json"), Json.encode(config.describe()) + "\n")
        val workload = Workload.generate(config)
        val connections = ThreadLocal.withInitial { Jedis(config.redisHost, config.redisPort) }

        val setup = Jedis(config.redisHost, config.redisPort)
        setup.flushDB()
        JsonlLog(out.resolve("commits-preload.jsonl")).use { log -> preload(setup, log) }
        createTopic()

        val state = RunState(System.nanoTime() + 2_000_000_000L)
        val findingsWriter = Files.newBufferedWriter(out.resolve("findings.jsonl"))
        val replay = ReplayGuard(retention = Duration.ofMinutes(10), maxTrackedIdentities = 250_000)
        val eventTime = EventTimeMonitor()
        val detector = if (config.mode != DetectorMode.OFF) {
            IntentTraceDetector(
                sinks = listOf(JsonLinesFindingSink(findingsWriter)),
                freshness = FreshnessCheck(oracle = RedisVersionOracle(connections, state), maxVersionLag = config.maxVersionLag),
                replay = replay,
                eventTime = eventTime,
                clock = clock
            )
        } else null
        val checker = if (config.mode == DetectorMode.ON_ASYNC) BackgroundChecker(detector!!, config, state) else null

        val partitions = (0 until config.partitions).map { TopicPartition(config.topic, it) }
        val consumer = KafkaConsumer<String, String>(consumerProperties())
        consumer.assign(partitions)
        consumer.seekToBeginning(partitions)
        partitions.forEach { consumer.position(it) }
        val producer = KafkaProducer<String, String>(producerProperties())

        val logs = mutableListOf<JsonlLog>()
        fun log(name: String) = JsonlLog(out.resolve(name)).also { logs += it }
        val saturationCount = AtomicLong()
        val failures = CopyOnWriteArrayList<Throwable>()
        fun worker(name: String, body: () -> Unit) = thread(name = name, start = false) {
            try { body() } catch (e: Throwable) { failures += e }
        }

        val consumerThread = worker("cache-consumer") {
            CacheConsumer(config, consumer, partitions, Jedis(config.redisHost, config.redisPort), detector,
                log("arrivals.jsonl"), log("applies.jsonl"), state, clock).run()
        }
        val producerThread = worker("primary-producer") {
            PrimaryProducer(config, workload, Jedis(config.redisHost, config.redisPort), producer,
                log("commits.jsonl"), state, clock).run()
        }
        val decisionThreads = (0 until config.decisionThreads).map { w ->
            worker("decision-$w") {
                DecisionWorker(w, config, workload, connections, detector, log("decisions-$w.jsonl"),
                    state, clock, saturationCount, checker).run()
            }
        }
        val evictorThread = worker("cache-evictor") {
            val redis = Jedis(config.redisHost, config.redisPort)
            val evictions = log("evictions.jsonl")
            for (eviction in workload.evictions) {
                state.waitUntil(eviction.atNanos)
                redis.del(cacheKey(eviction.user))
                evictions.write("user" to eviction.user, "evictedMicros" to Micros.of(clock.instant()))
            }
        }
        val measured = listOf(consumerThread) + decisionThreads + checker?.threads.orEmpty()
        val all = listOf(consumerThread) + decisionThreads + producerThread + evictorThread
        all.forEach { it.start() }

        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        state.waitUntil(config.warmupSeconds * 1_000_000_000L)
        val cpuBefore = measured.associate { it.name to threads.getThreadCpuTime(it.id) }
        val allocBefore = measured.associate { it.name to threads.getThreadAllocatedBytes(it.id) }
        state.measuring = true
        state.waitUntil(config.fixedRateSeconds * 1_000_000_000L)
        state.measuring = false
        val cpuAfter = measured.associate { it.name to threads.getThreadCpuTime(it.id) }
        val allocAfter = measured.associate { it.name to threads.getThreadAllocatedBytes(it.id) }

        (decisionThreads + evictorThread + producerThread).forEach { it.join() }
        checker?.drain()
        producer.flush()
        producer.close()
        state.producerDone = true
        consumerThread.join()
        logs.forEach { it.close() }
        findingsWriter.close()
        consumer.close()
        check(failures.isEmpty()) { "Benchmark thread failed: ${failures.first()}" }

        val heapAfterGc = heapAfterGc()
        val decisionOps = state.ofRole("decision").sumOf { it.operations }
        val eventOps = state.ofRole("consumer").sumOf { it.operations }
        fun merged(select: (ThreadSamples) -> LongList) = LongList().also { all -> state.samples.forEach { all.addAll(select(it)) } }.sorted()
        fun delta(before: Map<String, Long>, after: Map<String, Long>, names: List<String>) = names.sumOf { after.getValue(it) - before.getValue(it) }
        // Off-path checks are still decision work, so their CPU and allocation count per decision.
        val decisionNames = decisionThreads.map { it.name } + checker?.threads.orEmpty().map { it.name }
        val consumerNames = listOf(consumerThread.name)

        val metrics = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "mode" to config.mode.id,
            "runId" to config.runId,
            "measureSeconds" to config.measureSeconds,
            "decisions" to linkedMapOf(
                "completedInWindow" to decisionOps,
                "throughputPerSecond" to decisionOps.toDouble() / config.measureSeconds,
                "latencyMicros" to percentiles(merged { it.latency }),
                "serviceMicros" to percentiles(merged { it.service }),
                "cpuMicrosPerDecision" to delta(cpuBefore, cpuAfter, decisionNames) / 1_000.0 / decisionOps.coerceAtLeast(1),
                "allocatedBytesPerDecision" to delta(allocBefore, allocAfter, decisionNames).toDouble() / decisionOps.coerceAtLeast(1),
                "saturationThroughputPerSecond" to saturationCount.get().toDouble() / config.saturationSeconds.coerceAtLeast(1)
            ),
            "oracle" to linkedMapOf(
                "callMicros" to percentiles(merged { it.oracle }),
                "totalMicrosInWindow" to merged { it.oracle }.sum() / 1_000.0
            ),
            "events" to linkedMapOf(
                "processedInWindow" to eventOps,
                "throughputPerSecond" to eventOps.toDouble() / config.measureSeconds,
                "propagationMicros" to percentiles(merged { it.propagation }),
                "cpuMicrosPerEvent" to delta(cpuBefore, cpuAfter, consumerNames) / 1_000.0 / eventOps.coerceAtLeast(1),
                "allocatedBytesPerEvent" to delta(allocBefore, allocAfter, consumerNames).toDouble() / eventOps.coerceAtLeast(1),
                "sendFailures" to state.sendFailures.get()
            ),
            "memory" to linkedMapOf(
                "heapUsedAfterGcBytes" to heapAfterGc,
                "replayTrackedIdentities" to if (detector != null) replay.trackedIdentities else null,
                "replayEvictedIdentities" to if (detector != null) replay.evictedIdentities else null,
                "eventTimeTrackedArrivals" to if (detector != null) eventTime.trackedArrivals else null
            ),
            "detector" to detector?.let {
                linkedMapOf(
                    "decisionChecks" to if (checker != null) "background" else "inline",
                    "droppedChecks" to (checker?.droppedChecks ?: 0L),
                    "checkQueueDelayMicrosWholeRun" to if (checker != null) percentiles(merged { it.checkDelay }) else null,
                    "findings" to it.findingCounts().entries.associate { (k, v) -> k.id to v },
                    "checkFailures" to it.checkFailures,
                    "sinkFailures" to it.sinkFailures
                )
            },
            "environment" to linkedMapOf(
                "availableProcessors" to Runtime.getRuntime().availableProcessors(),
                "javaVersion" to System.getProperty("java.version"),
                "maxHeapBytes" to Runtime.getRuntime().maxMemory()
            )
        )
        Files.writeString(out.resolve("metrics.json"), Json.encode(metrics) + "\n")
        java.lang.ref.Reference.reachabilityFence(detector)
        setup.close()
    }

    private fun preload(redis: Jedis, log: JsonlLog) {
        val pipeline = redis.pipelined()
        val committed = Micros.of(clock.instant())
        for (user in 0 until config.users) {
            val payload = "p$user.1.preload"
            pipeline.zadd(primaryKey(user), committed.toDouble(), "1")
            pipeline.hset(cacheKey(user), mapOf("v" to "1", "c" to committed.toString(), "o" to committed.toString(), "p" to payload))
            log.write("eventId" to "preload-$user", "user" to user, "version" to 1, "committedMicros" to committed,
                "payload" to payload, "fault" to "preload")
        }
        pipeline.sync()
    }

    private fun createTopic() {
        Admin.create(Properties().apply { put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap) }).use {
            it.createTopics(listOf(NewTopic(config.topic, config.partitions, 1.toShort()))).all().get()
        }
    }

    private fun producerProperties() = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.LINGER_MS_CONFIG, "1")
    }

    private fun consumerProperties() = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrap)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
        put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "5")
    }

    private fun heapAfterGc(): Long {
        val memory = ManagementFactory.getMemoryMXBean()
        repeat(3) {
            System.gc()
            Thread.sleep(200)
        }
        return memory.heapMemoryUsage.used
    }
}
