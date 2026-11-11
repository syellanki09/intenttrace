package io.intenttrace.benchmarks

import io.intenttrace.evaluation.ReplayEvent
import io.intenttrace.evaluation.ReplayInvariant
import io.intenttrace.provenance.ReplayDeliveryEvidence
import io.intenttrace.provenance.ReplayProvenanceJson
import io.intenttrace.provenance.ReplayProvenanceRecord
import io.intenttrace.provenance.ReproductionMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

fun replayRecords(metadata: ReproductionMetadata): List<ReplayProvenanceRecord> {
    require(metadata.scenarioName == "duplicate-replay" && metadata.scenarioVersion == "1")
    val original = listOf(ReplayEvent("interest-a", 3), ReplayEvent("interest-b", 5))
    fun execute(caseId: String, stream: List<ReplayEvent>, deduplicate: Boolean): ReplayProvenanceRecord {
        val seen = mutableSetOf<String>()
        var total = 0L
        val evidence = stream.map { event ->
            val applied = if (deduplicate) seen.add(event.eventId) else true
            if (applied) total = Math.addExact(total, event.points)
            ReplayDeliveryEvidence(event.eventId, event.points, applied)
        }
        val evaluation = ReplayInvariant().evaluate(stream, evidence.filter { it.applied }.map { it.eventId }, total)
        return ReplayProvenanceRecord(reproduction = metadata, caseId = caseId,
            policy = if (deduplicate) "deduplicate-by-event-id" else "apply-every-delivery",
            deliveries = evidence, expectedTotal = evaluation.expectedTotal, observedTotal = total,
            status = evaluation.status, detected = evaluation.detected)
    }
    val replayed = original + original.first()
    return listOf(execute("control", original, false), execute("naive-replay", replayed, false),
        execute("idempotent-replay", replayed, true))
}

object DuplicateReplayMain {
    @JvmStatic fun main(args: Array<String>) {
        if (args.contentEquals(arrayOf("--help"))) {
            println("Usage: --output PATH --source-commit SHA (both optional)")
            println("Default output: benchmarks/build/results/duplicate-replay/results.jsonl")
            return
        }
        require(args.size % 2 == 0) { "Expected --output PATH and/or --source-commit SHA" }
        val options = mutableMapOf<String, String>()
        args.toList().chunked(2).forEach { (key, value) ->
            require(key in setOf("--output", "--source-commit") && value.isNotBlank() && !value.startsWith("--"))
            require(options.put(key, value) == null) { "Duplicate option: $key" }
        }
        val supplied = options["--source-commit"]
        val records = replayRecords(ReproductionMetadata("duplicate-replay", "1",
            supplied ?: git("rev-parse", "HEAD"), null,
            "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
            System.getProperty("java.version"),
            "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            if (supplied == null) mapOf("sourceDirty" to git("status", "--porcelain", "--untracked-files=normal").isNotEmpty().toString())
            else mapOf("sourceCommitOrigin" to "caller-supplied")))
        val output = Path.of(options["--output"] ?: "benchmarks/build/results/duplicate-replay/results.jsonl")
            .toAbsolutePath().normalize()
        Files.createDirectories(output.parent)
        val temporary = Files.createTempFile(output.parent, "duplicate-replay-", ".tmp")
        try {
            Files.writeString(temporary, records.joinToString("\n", postfix = "\n") { ReplayProvenanceJson.encode(it) })
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        records.forEach { println("${it.caseId}: ${it.observedTotal} (expected ${it.expectedTotal}), ${it.status}, detected=${it.detected}") }
        println("Wrote ${records.size} records to $output")
    }
}
