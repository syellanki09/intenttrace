package io.intenttrace.benchmarks

import io.intenttrace.evaluation.InvariantStatus
import io.intenttrace.provenance.ReplayProvenanceJson
import io.intenttrace.provenance.ReproductionMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DuplicateReplayScenarioTest {
    private val metadata = ReproductionMetadata("duplicate-replay", "1", "a".repeat(40), null,
        "test runtime", "17", "test OS", mapOf("sourceDirty" to "false"))

    @Test fun `control replay and idempotent execution preserve full delivery evidence`() {
        val records = replayRecords(metadata)
        assertEquals(listOf(8L, 11L, 8L), records.map { it.observedTotal })
        assertEquals(listOf(InvariantStatus.SATISFIED, InvariantStatus.VIOLATED, InvariantStatus.SATISFIED),
            records.map { it.status })
        assertEquals(records[1].deliveries.map { it.eventId }, records[2].deliveries.map { it.eventId })
        assertEquals(listOf(true, true, false), records[2].deliveries.map { it.applied })
        assertTrue(records.all { it.detected == null && it.expectedTotal == 8L })
        repeat(3) { assertEquals(records, replayRecords(metadata)) }
    }

    @Test fun `JSON round trip preserves canonical taxonomy and null detection`() {
        replayRecords(metadata).forEach { record ->
            val json = ReplayProvenanceJson.encode(record)
            assertTrue(json.contains("\"failureClassId\":\"duplicate-replay\""))
            assertTrue(json.contains("\"detected\":null"))
            assertEquals(record, ReplayProvenanceJson.decode(json))
        }
    }

    @Test fun `runner rejects unsupported duplicate and incomplete arguments`() {
        DuplicateReplayMain.main(arrayOf("--help"))
        for (args in listOf(arrayOf("--unknown", "x"), arrayOf("--output"),
            arrayOf("--output", "a", "--output", "b"))) {
            assertFailsWith<IllegalArgumentException> { DuplicateReplayMain.main(args) }
        }
    }
}
