package io.intenttrace.benchmarks

import io.intenttrace.evaluation.ClientContractDecision
import io.intenttrace.evaluation.ContractCompatibilityInvariant
import io.intenttrace.provenance.ContractClientEvidence
import io.intenttrace.provenance.ContractDurationUnit
import io.intenttrace.provenance.ContractProvenanceJson
import io.intenttrace.provenance.ContractProvenanceRecord
import io.intenttrace.provenance.ReproductionMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

fun contractRecords(metadata: ReproductionMetadata): List<ContractProvenanceRecord> {
    require(metadata.scenarioName == "contract-unit-drift" && metadata.scenarioVersion == "1")
    return ContractUnitDriftScenario().run().map { experiment ->
        val result = experiment.evaluation
        require(result == ContractCompatibilityInvariant().evaluate(result.reference, result.observed, result.detected))
        fun ClientContractDecision.evidence() = ContractClientEvidence(inputId, clientId, contractVersion, outcome)
        ContractProvenanceRecord(
            reproduction = metadata.copy(seed = null,
                configuration = metadata.configuration + mapOf("logicalDurationMillis" to "2000")),
            caseId = experiment.caseId,
            logicalDurationMillis = experiment.logicalDurationMillis,
            referenceWirePayload = experiment.referenceWirePayload,
            wirePayload = experiment.wirePayload,
            producerUnit = ContractDurationUnit.valueOf(experiment.producerUnit.name),
            consumerUnit = ContractDurationUnit.valueOf(experiment.consumerUnit.name),
            reference = result.reference.evidence(), observed = result.observed.evidence(),
            failureClassId = result.failureClass, status = result.status, detected = result.detected
        )
    }
}

internal fun runContractResults(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println("Usage: --output PATH --source-commit SHA (both optional; no --config support)")
        println("Default output: benchmarks/build/results/contract-unit-drift/results.jsonl")
        return
    }
    require(args.size % 2 == 0) { "Expected --output PATH and/or --source-commit SHA" }
    val options = mutableMapOf<String, String>()
    args.toList().chunked(2).forEach { (key, value) ->
        require(key in setOf("--output", "--source-commit") && value.isNotBlank() && !value.startsWith("--"))
        require(options.put(key, value) == null) { "Duplicate option: $key" }
    }
    val supplied = options["--source-commit"]
    val records = contractRecords(ReproductionMetadata(
        "contract-unit-drift", "1", supplied ?: git("rev-parse", "HEAD"), null,
        "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
        System.getProperty("java.version"),
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
        if (supplied == null) mapOf("sourceDirty" to git("status", "--porcelain", "--untracked-files=normal").isNotEmpty().toString())
        else mapOf("sourceCommitOrigin" to "caller-supplied")
    ))
    val output = Path.of(options["--output"] ?: "benchmarks/build/results/contract-unit-drift/results.jsonl").toAbsolutePath().normalize()
    Files.createDirectories(output.parent)
    val temporary = Files.createTempFile(output.parent, "contract-unit-drift-", ".tmp")
    try {
        Files.writeString(temporary, records.joinToString("\n", postfix = "\n") { ContractProvenanceJson.encode(it) })
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
    records.forEach { println("${it.caseId}: ${it.reference.outcome} -> ${it.observed.outcome}, ${it.status}, detected=${it.detected}") }
    println("Wrote ${records.size} records to $output")
}
