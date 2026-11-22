package io.intenttrace.benchmarks

import io.intenttrace.detector.CollectingFindingSink
import io.intenttrace.detector.ContextRead
import io.intenttrace.detector.EventTimeMonitor
import io.intenttrace.detector.FreshnessCheck
import io.intenttrace.detector.IntentTraceDetector
import io.intenttrace.detector.ReplayGuard
import io.intenttrace.detector.VersionStamp
import io.intenttrace.evaluation.InvariantStatus
import io.intenttrace.provenance.EventTimeProvenanceRecord
import io.intenttrace.provenance.ProvenanceRecord
import io.intenttrace.provenance.ReplayProvenanceRecord
import io.intenttrace.provenance.ReproductionMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * One fixture case evaluated twice: by the harness (ground truth) and by the
 * runtime detector, which sees only what a host system would see.
 */
@Serializable
data class DetectorAgreementRecord(
    val schemaVersion: Int = 1,
    val sourceCommitSha: String,
    val family: String,
    val caseId: String,
    val groundTruthStatus: InvariantStatus,
    val detected: Boolean,
    val findingClassIds: List<String>,
    val outcome: String
)

internal fun agreementOutcome(status: InvariantStatus, detected: Boolean, prevented: Boolean): String = when {
    status == InvariantStatus.VIOLATED && detected -> "true-positive"
    status == InvariantStatus.VIOLATED -> "false-negative"
    detected && prevented -> "detected-and-prevented"
    detected -> "false-positive"
    else -> "true-negative"
}

private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:10:00Z"), ZoneOffset.UTC)

/**
 * Runs the detector over the stale-context, duplicate-replay and event-time
 * fixtures. Contract unit drift is not covered: the detector has no contract
 * check yet, so those cases are left out rather than scored.
 */
fun detectorAgreementRecords(
    stale: List<ProvenanceRecord>,
    replay: List<ReplayProvenanceRecord>,
    eventTime: List<EventTimeProvenanceRecord>,
    sourceCommitSha: String
): List<DetectorAgreementRecord> {
    fun record(family: String, caseId: String, status: InvariantStatus, classes: List<String>, prevented: Boolean) =
        DetectorAgreementRecord(sourceCommitSha = sourceCommitSha, family = family, caseId = caseId,
            groundTruthStatus = status, detected = classes.isNotEmpty(), findingClassIds = classes,
            outcome = agreementOutcome(status, classes.isNotEmpty(), prevented))

    val staleRecords = stale.map { provenance ->
        val sink = CollectingFindingSink()
        // The context store is the authority the host would query for its latest committed version.
        val latest = provenance.latestEligibleContext?.let { VersionStamp(it.version, it.committedAt) }
        val detector = IntentTraceDetector(listOf(sink), freshness = FreshnessCheck({ _, _ -> latest }),
            clock = fixedClock)
        detector.checkDecision(ContextRead(provenance.decisionId, provenance.contextId, provenance.decidedAt,
            provenance.usedContext?.let { VersionStamp(it.version, it.committedAt) }))
        record("stale-context", provenance.execution.name.lowercase(), provenance.evaluation.status,
            sink.findings().map { it.failureClassId }, prevented = false)
    }

    val replayRecords = replay.map { provenance ->
        val sink = CollectingFindingSink()
        val detector = IntentTraceDetector(listOf(sink), replay = ReplayGuard(Duration.ofHours(1)), clock = fixedClock)
        val start = fixedClock.instant()
        provenance.deliveries.forEachIndexed { index, delivery ->
            // Observation only: the recorded policy, not the guard's verdict, decided what was applied.
            detector.onDelivery("replay-user", delivery.eventId, "points:${delivery.points}",
                start.plusSeconds(index.toLong()))
        }
        record("duplicate-replay", provenance.caseId, provenance.status, sink.findings().map { it.failureClassId },
            prevented = provenance.deliveries.any { !it.applied })
    }

    val eventTimeRecords = eventTime.map { provenance ->
        val sink = CollectingFindingSink()
        val detector = IntentTraceDetector(listOf(sink), eventTime = EventTimeMonitor(), clock = fixedClock)
        val key = "event-time-user"
        val reconcile = provenance.policy == "event-time"
        val (available, late) = provenance.deliveries.sortedBy { it.ingestedAt }
            .partition { !it.ingestedAt.isAfter(provenance.decidedAt) }
        var reflected: Instant? = null
        available.forEach { arrival ->
            detector.onArrival(key, arrival.eventId, arrival.occurredAt, arrival.ingestedAt)
            if (!reconcile || reflected == null || arrival.occurredAt.isAfter(reflected)) {
                detector.onApplied(key, arrival.eventId, arrival.occurredAt)
                reflected = arrival.occurredAt
            }
        }
        detector.checkDecision(ContextRead("${provenance.caseId}-decision", key, provenance.decidedAt, null),
            reflectedOccurredAt = reflected)
        late.forEach { detector.onArrival(key, it.eventId, it.occurredAt, it.ingestedAt) }
        record("event-time-ordering", provenance.caseId, provenance.status, sink.findings().map { it.failureClassId },
            prevented = false)
    }
    return staleRecords + replayRecords + eventTimeRecords
}

object DetectorAgreementMain {
    private val json = Json { encodeDefaults = true }

    @JvmStatic fun main(args: Array<String>) {
        if (args.contentEquals(arrayOf("--help"))) {
            println("Usage: --output PATH --source-commit SHA (both optional)")
            println("Default output: benchmarks/build/results/detector-agreement/results.jsonl")
            return
        }
        require(args.size % 2 == 0) { "Expected --output PATH and/or --source-commit SHA" }
        val options = mutableMapOf<String, String>()
        args.toList().chunked(2).forEach { (key, value) ->
            require(key in setOf("--output", "--source-commit") && value.isNotBlank() && !value.startsWith("--"))
            require(options.put(key, value) == null) { "Duplicate option: $key" }
        }
        val commit = options["--source-commit"] ?: git("rev-parse", "HEAD")
        fun metadata(name: String) = ReproductionMetadata(name, "1", commit, null,
            "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
            System.getProperty("java.version"),
            "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            emptyMap())
        val records = detectorAgreementRecords(StaleContextScenario().run(metadata("stale-context")),
            replayRecords(metadata("duplicate-replay")), eventTimeRecords(metadata("event-time-ordering")), commit)
        val output = Path.of(options["--output"] ?: "benchmarks/build/results/detector-agreement/results.jsonl")
            .toAbsolutePath().normalize()
        Files.createDirectories(output.parent)
        val temporary = Files.createTempFile(output.parent, "detector-agreement-", ".tmp")
        try {
            Files.writeString(temporary, records.joinToString("\n", postfix = "\n") {
                json.encodeToString(DetectorAgreementRecord.serializer(), it)
            })
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        records.forEach {
            println("${it.family}/${it.caseId}: ground truth ${it.groundTruthStatus}, detected=${it.detected} " +
                "${it.findingClassIds}, ${it.outcome}")
        }
        println("Wrote ${records.size} records to $output")
    }
}
