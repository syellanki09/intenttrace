package io.intenttrace.benchmarks

import io.intenttrace.provenance.ProvenanceJson
import io.intenttrace.provenance.ReproductionMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

fun main(args: Array<String>) {
    val options = RunnerOptions.parse(args)
    val commit = options.sourceCommit ?: git("rev-parse", "HEAD")
    val metadata = ReproductionMetadata(
        scenarioName = "stale-context",
        scenarioVersion = "1",
        sourceCommitSha = commit,
        seed = null,
        runtime = "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
        jdk = System.getProperty("java.version"),
        operatingSystem = "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
        configuration = if (options.sourceCommit == null) {
            mapOf("sourceDirty" to git("status", "--porcelain", "--untracked-files=normal").isNotEmpty().toString())
        } else {
            mapOf("sourceCommitOrigin" to "caller-supplied")
        }
    )
    val records = StaleContextScenario().run(metadata)
    val output = options.output.toAbsolutePath().normalize()
    Files.createDirectories(output.parent)
    val temporary = Files.createTempFile(output.parent, "stale-context-", ".jsonl.tmp")
    try {
        Files.writeString(temporary, records.joinToString("\n", postfix = "\n") { ProvenanceJson.encode(it) })
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
    records.forEach {
        println("${it.execution}: used=v${it.usedContext?.version}, latest=v${it.latestEligibleContext?.version}, " +
            "${it.evaluation.status}, detected=${it.evaluation.detected}")
    }
    println("Wrote ${records.size} records to $output")
}

internal data class RunnerOptions(val output: Path, val sourceCommit: String?) {
    companion object {
        fun parse(args: Array<String>): RunnerOptions {
            require(args.size % 2 == 0) { "Expected --output PATH and/or --source-commit SHA" }
            val options = mutableMapOf<String, String>()
            args.toList().chunked(2).forEach { (key, value) ->
                require(key in setOf("--output", "--source-commit")) { "Unknown option: $key" }
                require(value.isNotBlank() && !value.startsWith("--")) { "Missing value for $key" }
                require(options.put(key, value) == null) { "Duplicate option: $key" }
            }
            return RunnerOptions(
                Path.of(options["--output"] ?: "benchmarks/build/results/stale-context/results.jsonl"),
                options["--source-commit"]
            )
        }
    }
}

private fun git(vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git") + arguments).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    check(process.waitFor() == 0) { "Unable to read Git metadata; run from the checkout or supply --source-commit SHA" }
    return output
}
