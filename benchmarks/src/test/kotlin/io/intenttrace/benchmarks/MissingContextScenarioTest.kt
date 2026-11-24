package io.intenttrace.benchmarks

import io.intenttrace.evaluation.InvariantStatus
import io.intenttrace.provenance.MissingContextProvenanceJson
import io.intenttrace.provenance.ReproductionMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MissingContextScenarioTest {
    private val metadata = ReproductionMetadata("missing-context", "1", "a".repeat(40), null,
        "test runtime", "17", "test OS", mapOf("sourceDirty" to "false"))

    @Test fun `control silent default and recorded degradation are classified from host evidence`() {
        val records = missingContextRecords(metadata)
        assertEquals(listOf("control", "silent-default", "recorded-degradation"), records.map { it.caseId })
        assertEquals(listOf("gold", "standard", "standard"), records.map { it.usedValue })
        assertEquals(listOf(InvariantStatus.SATISFIED, InvariantStatus.VIOLATED, InvariantStatus.SATISFIED),
            records.map { it.status })
        assertEquals(listOf(false, true, true), records.map { it.delivered == null })
        assertEquals(listOf(null, null, "tier-service"), records.map { it.degradation?.source })
        assertTrue(records.all { it.detected == null && it.required.value == "gold" })
        repeat(3) { assertEquals(records, missingContextRecords(metadata)) }
    }

    @Test fun `JSON round trip preserves canonical taxonomy and null detection`() {
        missingContextRecords(metadata).forEach { record ->
            val json = MissingContextProvenanceJson.encode(record)
            assertTrue(json.contains("\"failureClassId\":\"missing-context\""))
            assertTrue(json.contains("\"detected\":null"))
            assertEquals(record, MissingContextProvenanceJson.decode(json))
        }
    }

    @Test fun `scenario identity is enforced`() {
        assertFailsWith<IllegalArgumentException> { missingContextRecords(metadata.copy(scenarioName = "other")) }
        assertFailsWith<IllegalArgumentException> { missingContextRecords(metadata.copy(scenarioVersion = "2")) }
    }

    @Test fun `runner rejects unsupported duplicate and incomplete arguments`() {
        MissingContextMain.main(arrayOf("--help"))
        for (args in listOf(arrayOf("--unknown", "x"), arrayOf("--output"),
            arrayOf("--output", "a", "--output", "b"))) {
            assertFailsWith<IllegalArgumentException> { MissingContextMain.main(args) }
        }
    }
}
