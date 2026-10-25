package io.intenttrace.benchmarks

import io.intenttrace.evaluation.InvariantStatus
import io.intenttrace.provenance.ExecutionKind
import io.intenttrace.provenance.ProvenanceJson
import io.intenttrace.provenance.ReproductionMetadata
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StaleContextScenarioTest {
    private val metadata = ReproductionMetadata(
        "stale-context", "1", "a".repeat(40), null, "test-runtime", "17", "test-os", emptyMap()
    )

    @Test
    fun `scenario produces paired independently evaluated and repeatable records`() {
        val scenario = StaleContextScenario()
        val records = scenario.run(metadata)
        assertEquals(listOf(ExecutionKind.CONTROL, ExecutionKind.INJECTED), records.map { it.execution })
        assertEquals(listOf(InvariantStatus.SATISFIED, InvariantStatus.VIOLATED), records.map { it.evaluation.status })
        assertEquals(listOf(2L, 1L), records.map { it.usedContext?.version })
        assertEquals(listOf(2L, 2L), records.map { it.latestEligibleContext?.version })
        assertEquals(listOf("technology", "travel"), records.map { it.outcome })
        records.forEach {
            assertNull(it.evaluation.detected)
            assertNull(it.degradationReason)
            assertEquals("stale-context", it.evaluation.failureClass.id)
            assertEquals("1", it.reproduction.configuration["pinnedVersion"])
        }
        repeat(3) { assertEquals(records, scenario.run(metadata)) }
    }

    @Test
    fun `entry point writes two parseable JSONL records and replaces earlier output`() {
        val directory = Files.createTempDirectory("intenttrace-runner-test")
        val output = directory.resolve("results.jsonl")
        try {
            val args = arrayOf("--output", output.toString(), "--source-commit", "b".repeat(40))
            main(args)
            val first = Files.readString(output)
            val records = Files.readAllLines(output).map(ProvenanceJson::decode)
            assertEquals(2, records.size)
            assertEquals("b".repeat(40), records.first().reproduction.sourceCommitSha)
            assertEquals("caller-supplied", records.first().reproduction.configuration["sourceCommitOrigin"])
            main(args)
            assertEquals(first, Files.readString(output))
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `invalid arguments and source metadata fail without creating output`() {
        for (args in listOf(arrayOf("--unknown", "x"), arrayOf("--output"),
            arrayOf("--output", "x", "--output", "y"))) {
            assertFailsWith<IllegalArgumentException> { RunnerOptions.parse(args) }
        }
        assertFailsWith<IllegalArgumentException> { main(arrayOf("--source-commit", "invalid")) }
        assertFailsWith<IllegalArgumentException> { StaleContextScenario().run(metadata.copy(scenarioVersion = "2")) }
    }
}
