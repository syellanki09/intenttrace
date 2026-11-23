package io.intenttrace.systembenchmark

import java.nio.file.Path

/** Whether, and where, the host service runs the IntentTrace detector. */
enum class DetectorMode(val id: String) {
    /** No detector calls anywhere. */
    OFF("off"),

    /** Decision checks, including the oracle query, run inside the decision path before it returns. */
    ON("on"),

    /**
     * Decision checks are handed to a background checker and the decision returns
     * at once. The event consumer still calls the detector inline, because its
     * verdict decides whether to apply a delivery.
     */
    ON_ASYNC("on-async");

    companion object {
        fun parse(value: String): DetectorMode =
            entries.firstOrNull { it.id == value }
                ?: throw IllegalArgumentException("Unknown mode '$value'; use ${entries.joinToString { it.id }}")
    }
}

/**
 * Settings for one benchmark run. Every random choice in the workload derives
 * from [seed], so two runs with the same settings send the same synthetic
 * events, decisions, faults and evictions on the same schedule.
 */
data class BenchmarkConfig(
    val mode: DetectorMode,
    val output: Path,
    val runId: String,
    val seed: Long = 42,
    val users: Int = 10_000,
    val zipfExponent: Double = 0.9,
    val eventsPerSecond: Int = 2_000,
    val decisionsPerSecond: Int = 2_000,
    val warmupSeconds: Int = 15,
    val measureSeconds: Int = 30,
    val saturationSeconds: Int = 10,
    val decisionThreads: Int = 4,
    val checkerThreads: Int = 1,
    val checkerQueue: Int = 50_000,
    val partitions: Int = 4,
    val maxVersionLag: Long = 0,
    val delayedSendRate: Double = 0.01,
    val delayedSendMillis: Long = 150,
    val duplicateRate: Double = 0.01,
    val duplicateAfterMillis: Long = 80,
    val conflictingRate: Double = 0.001,
    val conflictingAfterMillis: Long = 40,
    val lostWritePerMille: Int = 5,
    val evictionsPerSecond: Int = 50,
    val kafkaBootstrap: String = "127.0.0.1:9092",
    val redisHost: String = "127.0.0.1",
    val redisPort: Int = 6379
) {
    init {
        require(runId.matches(Regex("[A-Za-z0-9._-]+"))) { "Run id may contain only letters, digits, dot, underscore and dash" }
        require(users > 0 && eventsPerSecond > 0 && decisionsPerSecond > 0) { "Users and rates must be positive" }
        require(warmupSeconds >= 0 && measureSeconds > 0 && saturationSeconds >= 0) { "Measure window must be positive" }
        require(checkerThreads > 0 && checkerQueue > 0) { "The background checker needs a thread and a queue" }
        require(decisionThreads > 0 && partitions > 0) { "Threads and partitions must be positive" }
        require(maxVersionLag >= 0) { "Maximum version lag must not be negative" }
        listOf(delayedSendRate, duplicateRate, conflictingRate).forEach { require(it in 0.0..1.0) { "Fault rates are probabilities" } }
        require(lostWritePerMille in 0..1000) { "Lost writes are given per thousand events" }
        require(evictionsPerSecond >= 0) { "Eviction rate must not be negative" }
    }

    val fixedRateSeconds: Int get() = warmupSeconds + measureSeconds
    val totalSeconds: Int get() = warmupSeconds + measureSeconds + saturationSeconds
    val topic: String get() = "context-updates-$runId"

    /** The settings as a flat map, written next to the results. */
    fun describe(): Map<String, Any> = linkedMapOf(
        "mode" to mode.id, "runId" to runId, "seed" to seed, "users" to users, "zipfExponent" to zipfExponent,
        "eventsPerSecond" to eventsPerSecond, "decisionsPerSecond" to decisionsPerSecond,
        "warmupSeconds" to warmupSeconds, "measureSeconds" to measureSeconds, "saturationSeconds" to saturationSeconds,
        "decisionThreads" to decisionThreads, "checkerThreads" to checkerThreads, "checkerQueue" to checkerQueue,
        "partitions" to partitions, "maxVersionLag" to maxVersionLag,
        "delayedSendRate" to delayedSendRate, "delayedSendMillis" to delayedSendMillis,
        "duplicateRate" to duplicateRate, "duplicateAfterMillis" to duplicateAfterMillis,
        "conflictingRate" to conflictingRate, "conflictingAfterMillis" to conflictingAfterMillis,
        "lostWritePerMille" to lostWritePerMille, "evictionsPerSecond" to evictionsPerSecond
    )

    companion object {
        /** Parses `--name=value` arguments. `--mode`, `--output` and `--run-id` are required. */
        fun parse(args: Array<String>): BenchmarkConfig {
            val values = args.associate { arg ->
                require(arg.startsWith("--") && '=' in arg) { "Arguments look like --name=value, got '$arg'" }
                arg.removePrefix("--").substringBefore('=') to arg.substringAfter('=')
            }
            val known = setOf(
                "mode", "output", "run-id", "seed", "users", "zipf-exponent", "events-per-second",
                "decisions-per-second", "warmup-seconds", "measure-seconds", "saturation-seconds",
                "decision-threads", "partitions", "max-version-lag", "evictions-per-second",
                "kafka-bootstrap", "redis-host", "redis-port"
            )
            val unknown = values.keys - known
            require(unknown.isEmpty()) { "Unknown arguments: ${unknown.sorted().joinToString()}" }
            fun required(name: String) = values[name] ?: throw IllegalArgumentException("--$name is required")
            val defaults = BenchmarkConfig(DetectorMode.OFF, Path.of("."), "defaults")
            return BenchmarkConfig(
                mode = DetectorMode.parse(required("mode")),
                output = Path.of(required("output")),
                runId = required("run-id"),
                seed = values["seed"]?.toLong() ?: defaults.seed,
                users = values["users"]?.toInt() ?: defaults.users,
                zipfExponent = values["zipf-exponent"]?.toDouble() ?: defaults.zipfExponent,
                eventsPerSecond = values["events-per-second"]?.toInt() ?: defaults.eventsPerSecond,
                decisionsPerSecond = values["decisions-per-second"]?.toInt() ?: defaults.decisionsPerSecond,
                warmupSeconds = values["warmup-seconds"]?.toInt() ?: defaults.warmupSeconds,
                measureSeconds = values["measure-seconds"]?.toInt() ?: defaults.measureSeconds,
                saturationSeconds = values["saturation-seconds"]?.toInt() ?: defaults.saturationSeconds,
                decisionThreads = values["decision-threads"]?.toInt() ?: defaults.decisionThreads,
                partitions = values["partitions"]?.toInt() ?: defaults.partitions,
                maxVersionLag = values["max-version-lag"]?.toLong() ?: defaults.maxVersionLag,
                evictionsPerSecond = values["evictions-per-second"]?.toInt() ?: defaults.evictionsPerSecond,
                kafkaBootstrap = values["kafka-bootstrap"] ?: defaults.kafkaBootstrap,
                redisHost = values["redis-host"] ?: defaults.redisHost,
                redisPort = values["redis-port"]?.toInt() ?: defaults.redisPort
            )
        }
    }
}
