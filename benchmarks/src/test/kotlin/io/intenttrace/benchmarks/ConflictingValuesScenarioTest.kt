package io.intenttrace.benchmarks

import io.intenttrace.evaluation.InvariantStatus
import io.intenttrace.provenance.ConflictingValuesProvenanceJson
import io.intenttrace.provenance.ReproductionMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConflictingValuesScenarioTest {
    private val metadata = ReproductionMetadata("conflicting-values", "1", "a".repeat(40), null,
        "test runtime", "17", "test OS", mapOf("sourceDirty" to "false"))

    @Test fun `silent picks violate and recorded resolution satisfies`() {
        val records = conflictingValuesRecords(metadata)
        assertEquals(listOf("control", "last-arrival-wins", "recorded-resolution", "same-version-divergence"),
            records.map { it.caseId })
        assertEquals(listOf("cache=500", "cache=750", "primary=500", "cache=750"),
            records.map { "${it.usedSource}=${it.usedValue}" })
        assertEquals(listOf(InvariantStatus.SATISFIED, InvariantStatus.VIOLATED, InvariantStatus.SATISFIED,
            InvariantStatus.VIOLATED), records.map { it.status })
        assertEquals(listOf(null, null, "prefer-primary", null), records.map { it.resolution?.rule })
        assertEquals(listOf(7L, 8L, 8L, 7L), records.map { it.observations.last().version })
        assertTrue(records.all { it.detected == null && it.observations.first().source == "primary" })
        repeat(3) { assertEquals(records, conflictingValuesRecords(metadata)) }
    }

    @Test fun `JSON round trip preserves canonical taxonomy and null detection`() {
        conflictingValuesRecords(metadata).forEach { record ->
            val json = ConflictingValuesProvenanceJson.encode(record)
            assertTrue(json.contains("\"failureClassId\":\"conflicting-values\""))
            assertTrue(json.contains("\"detected\":null"))
            assertEquals(record, ConflictingValuesProvenanceJson.decode(json))
        }
    }

    @Test fun `scenario identity is enforced`() {
        assertFailsWith<IllegalArgumentException> { conflictingValuesRecords(metadata.copy(scenarioName = "other")) }
    }

    @Test fun `runner rejects unsupported duplicate and incomplete arguments`() {
        ConflictingValuesMain.main(arrayOf("--help"))
        for (args in listOf(arrayOf("--unknown", "x"), arrayOf("--output"),
            arrayOf("--output", "a", "--output", "b"))) {
            assertFailsWith<IllegalArgumentException> { ConflictingValuesMain.main(args) }
        }
    }
}
