package io.intenttrace.benchmarks

import io.intenttrace.evaluation.FailureClass
import io.intenttrace.evaluation.InvariantStatus
import io.intenttrace.provenance.EventTimeDeliveryEvidence
import io.intenttrace.provenance.EventTimeProvenanceJson
import io.intenttrace.provenance.ReproductionMetadata
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventTimeScenarioTest {
    private val metadata = ReproductionMetadata("event-time-ordering", "1", "a".repeat(40), null,
        "test runtime", "17", "test OS", mapOf("sourceDirty" to "false"))

    @Test fun `reordering corrupts arrival state while reconciliation preserves latest event`() {
        val records = eventTimeRecords(metadata)
        assertEquals(listOf("technology", "travel", "technology", "travel"), records.map { it.observedValue })
        assertEquals(listOf(InvariantStatus.SATISFIED, InvariantStatus.VIOLATED,
            InvariantStatus.SATISFIED, InvariantStatus.VIOLATED), records.map { it.status })
        assertEquals(records[1].deliveries, records[2].deliveries)
        assertEquals(FailureClass.DELAYED_EVENTS, records.last().failureClassId)
        assertTrue(records.take(3).all { it.failureClassId == FailureClass.STALE_CONTEXT })
        assertTrue(records.all { it.expectedValue == "technology" && it.detected == null })
        repeat(3) { assertEquals(records, eventTimeRecords(metadata)) }
    }

    @Test fun `arrival boundary is inclusive and later arrivals cannot leak into selection`() {
        val boundary = Instant.parse("2026-01-01T00:00:40Z")
        val older = EventTimeDeliveryEvidence("a", boundary.minusSeconds(20), boundary, "travel")
        val newer = EventTimeDeliveryEvidence("b", boundary.minusSeconds(10), boundary.plusNanos(1), "technology")
        for (reconcile in listOf(false, true)) {
            assertEquals(older, selectEvent(listOf(newer, older), boundary, reconcile))
            assertEquals(newer, selectEvent(listOf(newer, older), boundary.plusNanos(1), reconcile))
        }
    }

    @Test fun `provenance round trip retains all three clocks and explicit null detection`() {
        eventTimeRecords(metadata).forEach { record ->
            val json = EventTimeProvenanceJson.encode(record)
            assertTrue(json.contains("\"detected\":null"))
            assertEquals(record, EventTimeProvenanceJson.decode(json))
        }
    }

    @Test fun `invalid timestamps and runner arguments fail immediately`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        assertFailsWith<IllegalArgumentException> { EventTimeDeliveryEvidence("a", now, now.minusNanos(1), "x") }
        EventTimeMain.main(arrayOf("--help"))
        for (args in listOf(arrayOf("--config", "x"), arrayOf("--output"),
            arrayOf("--output", "a", "--output", "b"))) {
            assertFailsWith<IllegalArgumentException> { EventTimeMain.main(args) }
        }
    }
}
