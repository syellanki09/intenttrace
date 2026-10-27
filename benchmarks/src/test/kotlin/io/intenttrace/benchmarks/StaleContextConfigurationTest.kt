package io.intenttrace.benchmarks

import io.intenttrace.evaluation.InvariantStatus
import io.intenttrace.provenance.ReproductionMetadata
import io.intenttrace.provenance.ProvenanceJson
import java.nio.file.Files
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StaleContextConfigurationTest {
    private val metadata = ReproductionMetadata(
        "stale-context", "1", "a".repeat(40), null, "runtime", "17", "os", emptyMap()
    )

    @Test
    fun `custom configuration changes inputs and records every effective value`() {
        val configuration = StaleContextConfiguration.decode("""{
            "contextId":"custom-user", "olderVersion":4, "latestVersion":9,
            "olderCategory":"science", "latestCategory":"music"
        }""")
        val records = StaleContextScenario().run(metadata, configuration)
        assertEquals(listOf(9L, 4L), records.map { it.usedContext?.version })
        assertEquals(listOf("music", "science"), records.map { it.outcome })
        assertEquals(listOf(InvariantStatus.SATISFIED, InvariantStatus.VIOLATED), records.map { it.evaluation.status })
        records.forEach {
            assertEquals("custom-user", it.contextId)
            assertEquals(configuration.metadata(), it.reproduction.configuration)
        }
        assertEquals(records, StaleContextScenario().run(metadata, configuration))
    }

    @Test
    fun `invalid configuration is rejected before execution`() {
        for (json in listOf("""{"configurationVersion":2}""", """{"contextId":" "}""",
            """{"olderVersion":0}""", """{"olderVersion":2}""",
            """{"olderCategory":"technology"}""", """{"latestCategory":""}""",
            """{"latestCommittedAt":"2026-01-02T00:00:00Z"}""")) {
            assertFailsWith<IllegalArgumentException> { StaleContextConfiguration.decode(json) }
        }
        assertFailsWith<SerializationException> { StaleContextConfiguration.decode("""{"typo":1}""") }
        assertFailsWith<java.time.format.DateTimeParseException> {
            StaleContextConfiguration.decode("""{"decidedAt":"not-a-time"}""")
        }
    }

    @Test
    fun `equal commit timestamps and inclusive decision boundary are valid`() {
        val boundary = Instant.parse("2026-01-01T00:00:01Z")
        val configuration = StaleContextConfiguration(olderCommittedAt = boundary,
            latestCommittedAt = boundary, decidedAt = boundary)
        assertEquals(listOf(2L, 1L), StaleContextScenario().run(metadata, configuration).map { it.usedContext?.version })
    }

    @Test
    fun `runner reads config file and protects it from output overwrite`() {
        val directory = Files.createTempDirectory("intenttrace-config-test")
        val config = directory.resolve("config.json")
        val output = directory.resolve("results.jsonl")
        try {
            Files.writeString(config, """{"olderVersion":3,"latestVersion":7}""")
            main(arrayOf("--config", config.toString(), "--output", output.toString(), "--source-commit", "a".repeat(40)))
            assertEquals(listOf(7L, 3L), Files.readAllLines(output).map { ProvenanceJson.decode(it).usedContext?.version })
            assertFailsWith<IllegalArgumentException> {
                main(arrayOf("--config", config.toString(), "--output", config.toString()))
            }
            assertEquals("""{"olderVersion":3,"latestVersion":7}""", Files.readString(config))
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(config)
            Files.deleteIfExists(directory)
        }
    }
}
