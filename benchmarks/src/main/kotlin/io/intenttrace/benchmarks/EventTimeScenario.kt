package io.intenttrace.benchmarks

import io.intenttrace.evaluation.EventTimeInvariant
import io.intenttrace.evaluation.TimedEvent
import io.intenttrace.provenance.EventTimeDeliveryEvidence
import io.intenttrace.provenance.EventTimeProvenanceJson
import io.intenttrace.provenance.EventTimeProvenanceRecord
import io.intenttrace.provenance.ReproductionMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Accumulates only arrivals available by the inclusive decision boundary. */
internal fun selectEvent(deliveries: List<EventTimeDeliveryEvidence>, boundary: Instant,
                         reconcileEventTime: Boolean): EventTimeDeliveryEvidence? {
    var selected: EventTimeDeliveryEvidence? = null
    deliveries.sortedBy { it.ingestedAt }.filter { it.ingestedAt <= boundary }.forEach { arrival ->
        if (!reconcileEventTime || selected == null || arrival.occurredAt > selected!!.occurredAt) selected = arrival
    }
    return selected
}

fun eventTimeRecords(metadata: ReproductionMetadata): List<EventTimeProvenanceRecord> {
    require(metadata.scenarioName == "event-time-ordering" && metadata.scenarioVersion == "1")
    val start = Instant.parse("2026-01-01T00:00:00Z")
    val boundary = start.plusSeconds(40)
    val older = EventTimeDeliveryEvidence("interest-a", start.plusSeconds(10), start.plusSeconds(12), "travel")
    val newer = EventTimeDeliveryEvidence("interest-b", start.plusSeconds(20), start.plusSeconds(22), "technology")
    fun execute(id: String, arrivals: List<EventTimeDeliveryEvidence>, reconcile: Boolean): EventTimeProvenanceRecord {
        fun EventTimeDeliveryEvidence.event() = TimedEvent(eventId, occurredAt, ingestedAt, value)
        val observed = selectEvent(arrivals, boundary, reconcile)?.event()
        val result = EventTimeInvariant().evaluate(arrivals.map { it.event() }, boundary, observed)
        return EventTimeProvenanceRecord(reproduction = metadata, caseId = id,
            policy = if (reconcile) "event-time" else "arrival-order", decidedAt = boundary,
            deliveries = arrivals.sortedBy { it.ingestedAt }, expectedEventId = result.expected?.eventId,
            expectedValue = result.expected?.value, observedEventId = observed?.eventId,
            observedValue = observed?.value, failureClassId = result.failureClass,
            status = result.status, detected = result.detected)
    }
    val reordered = listOf(newer, older.copy(ingestedAt = start.plusSeconds(32)))
    return listOf(execute("control", listOf(older, newer), false),
        execute("reordered-arrival", reordered, false), execute("event-time-reconciliation", reordered, true),
        execute("late-arrival", listOf(older, newer.copy(ingestedAt = start.plusSeconds(45))), true))
}

object EventTimeMain {
    @JvmStatic fun main(args: Array<String>) {
        if (args.contentEquals(arrayOf("--help"))) {
            println("Usage: --output PATH --source-commit SHA (both optional)")
            println("Default output: benchmarks/build/results/event-time-ordering/results.jsonl")
            return
        }
        require(args.size % 2 == 0) { "Expected --output PATH and/or --source-commit SHA" }
        val options = mutableMapOf<String, String>()
        args.toList().chunked(2).forEach { (key, value) ->
            require(key in setOf("--output", "--source-commit") && value.isNotBlank() && !value.startsWith("--"))
            require(options.put(key, value) == null) { "Duplicate option: $key" }
        }
        val supplied = options["--source-commit"]
        val records = eventTimeRecords(ReproductionMetadata("event-time-ordering", "1",
            supplied ?: git("rev-parse", "HEAD"), null,
            "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
            System.getProperty("java.version"),
            "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            if (supplied == null) mapOf("sourceDirty" to git("status", "--porcelain", "--untracked-files=normal").isNotEmpty().toString())
            else mapOf("sourceCommitOrigin" to "caller-supplied")))
        val output = Path.of(options["--output"] ?: "benchmarks/build/results/event-time-ordering/results.jsonl")
            .toAbsolutePath().normalize()
        Files.createDirectories(output.parent)
        val temporary = Files.createTempFile(output.parent, "event-time-ordering-", ".tmp")
        try {
            Files.writeString(temporary, records.joinToString("\n", postfix = "\n") { EventTimeProvenanceJson.encode(it) })
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        records.forEach { println("${it.caseId}: ${it.observedValue} (expected ${it.expectedValue}), ${it.status}, detected=${it.detected}") }
        println("Wrote ${records.size} records to $output")
    }
}
