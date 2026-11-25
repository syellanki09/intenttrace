package io.intenttrace.benchmarks

import io.intenttrace.provenance.ReproductionMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Shared command line for the fixed synthetic scenarios: optional --output and
 * --source-commit, atomic JSON Lines output, and one summary line per case.
 */
internal fun <T> runFixedScenario(
    scenarioName: String,
    args: Array<String>,
    records: (ReproductionMetadata) -> List<T>,
    encode: (T) -> String,
    summary: (T) -> String
) {
    val defaultOutput = "benchmarks/build/results/$scenarioName/results.jsonl"
    if (args.contentEquals(arrayOf("--help"))) {
        println("Usage: --output PATH --source-commit SHA (both optional)")
        println("Default output: $defaultOutput")
        return
    }
    require(args.size % 2 == 0) { "Expected --output PATH and/or --source-commit SHA" }
    val options = mutableMapOf<String, String>()
    args.toList().chunked(2).forEach { (key, value) ->
        require(key in setOf("--output", "--source-commit") && value.isNotBlank() && !value.startsWith("--"))
        require(options.put(key, value) == null) { "Duplicate option: $key" }
    }
    val supplied = options["--source-commit"]
    val built = records(ReproductionMetadata(scenarioName, "1",
        supplied ?: git("rev-parse", "HEAD"), null,
        "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}",
        System.getProperty("java.version"),
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
        if (supplied == null) mapOf("sourceDirty" to git("status", "--porcelain", "--untracked-files=normal").isNotEmpty().toString())
        else mapOf("sourceCommitOrigin" to "caller-supplied")))
    val output = Path.of(options["--output"] ?: defaultOutput).toAbsolutePath().normalize()
    Files.createDirectories(output.parent)
    val temporary = Files.createTempFile(output.parent, "$scenarioName-", ".tmp")
    try {
        Files.writeString(temporary, built.joinToString("\n", postfix = "\n") { encode(it) })
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
    built.forEach { println(summary(it)) }
    println("Wrote ${built.size} records to $output")
}
