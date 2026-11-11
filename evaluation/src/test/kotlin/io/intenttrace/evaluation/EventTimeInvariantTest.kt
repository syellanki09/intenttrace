package io.intenttrace.evaluation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EventTimeInvariantTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val older = TimedEvent("a", start.plusSeconds(10), start.plusSeconds(10), "travel")
    private val newer = TimedEvent("b", start.plusSeconds(20), start.plusSeconds(20), "technology")

    @Test fun `latest event time defines truth with inclusive boundary`() {
        val result = EventTimeInvariant().evaluate(listOf(newer, older), newer.occurredAt, older)
        assertEquals(newer, result.expected)
        assertEquals(InvariantStatus.VIOLATED, result.status)
        assertEquals(FailureClass.STALE_CONTEXT, result.failureClass)
        assertNull(result.detected)
        assertEquals(InvariantStatus.SATISFIED,
            EventTimeInvariant().evaluate(listOf(older, newer), newer.occurredAt, newer).status)
    }

    @Test fun `future events are excluded and absence has an explicit status`() {
        assertEquals(older, EventTimeInvariant().evaluate(listOf(older, newer), start.plusSeconds(15), older).expected)
        assertEquals(InvariantStatus.NOT_APPLICABLE,
            EventTimeInvariant().evaluate(listOf(older), start, null).status)
        assertEquals(InvariantStatus.NOT_APPLICABLE, EventTimeInvariant().evaluate(emptyList(), start, null).status)
        assertEquals(InvariantStatus.VIOLATED,
            EventTimeInvariant().evaluate(listOf(older), older.occurredAt, null).status)
    }

    @Test fun `detection observations never change ground truth`() {
        for (signal in listOf(null, false, true)) {
            val result = EventTimeInvariant().evaluate(listOf(older, newer), newer.occurredAt, older, signal)
            assertEquals(InvariantStatus.VIOLATED, result.status)
            assertEquals(signal, result.detected)
        }
        assertEquals(InvariantStatus.SATISFIED,
            EventTimeInvariant().evaluate(listOf(older), older.occurredAt, older, true).status)
    }

    @Test fun `late availability has a distinct canonical failure class`() {
        val late = newer.copy(ingestedAt = start.plusSeconds(30))
        val result = EventTimeInvariant().evaluate(listOf(older, late), start.plusSeconds(25), older)
        assertEquals(InvariantStatus.VIOLATED, result.status)
        assertEquals(FailureClass.DELAYED_EVENTS, result.failureClass)
    }

    @Test fun `ambiguous or foreign evidence fails immediately`() {
        for (events in listOf(listOf(older, older), listOf(older, older.copy(eventId = "other")))) {
            assertFailsWith<IllegalArgumentException> { EventTimeInvariant().evaluate(events, newer.occurredAt, older) }
        }
        assertFailsWith<IllegalArgumentException> {
            EventTimeInvariant().evaluate(listOf(older), newer.occurredAt, newer)
        }
        val late = newer.copy(ingestedAt = start.plusSeconds(30))
        assertFailsWith<IllegalArgumentException> {
            EventTimeInvariant().evaluate(listOf(older, late), start.plusSeconds(25), late)
        }
    }
}
