package io.intenttrace.detector

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventTimeMonitorTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private fun at(seconds: Long) = start.plusSeconds(seconds)

    @Test fun `in-order application produces no finding`() {
        val monitor = EventTimeMonitor()
        assertNull(monitor.onApplied("user-1", "a", at(10), at(12)))
        assertNull(monitor.onApplied("user-1", "b", at(20), at(22)))
    }

    @Test fun `older event replacing newer state is a stale-context regression`() {
        val monitor = EventTimeMonitor()
        monitor.onApplied("user-1", "b", at(20), at(22))
        val finding = monitor.onApplied("user-1", "a", at(10), at(32))!!
        assertEquals(FailureClassId.STALE_CONTEXT, finding.failureClass)
        assertEquals("b", finding.evidence["replacedEventId"])
        assertEquals("a", finding.eventId)
    }

    @Test fun `event that occurred before a decision but arrived after it is delayed`() {
        val monitor = EventTimeMonitor()
        monitor.onDecision("user-1", "decision-1", at(40), reflectedOccurredAt = at(10))
        val finding = monitor.onArrival("user-1", "b", at(20), at(45)).single()
        assertEquals(FailureClassId.DELAYED_EVENTS, finding.failureClass)
        assertEquals("decision-1", finding.decisionId)
        assertEquals(at(40).toString(), finding.evidence["decidedAt"])
    }

    @Test fun `arrivals that could not have affected a decision are ignored`() {
        val monitor = EventTimeMonitor()
        monitor.onDecision("user-1", "decision-1", at(40), reflectedOccurredAt = at(20))
        assertTrue(monitor.onArrival("user-1", "early", at(30), at(40)).isEmpty(), "arrived at the boundary")
        assertTrue(monitor.onArrival("user-1", "after", at(41), at(45)).isEmpty(), "occurred after the decision")
        assertTrue(monitor.onArrival("user-1", "older", at(15), at(45)).isEmpty(), "older than what it reflected")
        assertTrue(monitor.onArrival("user-2", "other", at(15), at(45)).isEmpty(), "different key")
    }

    @Test fun `only recent decisions per key are retained`() {
        val monitor = EventTimeMonitor(maxDecisionsPerKey = 2)
        listOf(10L, 20L, 30L).forEach { monitor.onDecision("user-1", "d$it", at(it), null) }
        assertEquals(listOf("d20", "d30"), monitor.onArrival("user-1", "x", at(5), at(50)).map { it.decisionId })
    }

    @Test fun `skewed clocks are tolerated and invalid configuration is rejected`() {
        val monitor = EventTimeMonitor()
        monitor.onDecision("user-1", "d", at(10), at(11))
        assertTrue(monitor.onArrival("user-1", "x", at(10), at(5)).isEmpty())
        assertFailsWith<IllegalArgumentException> { EventTimeMonitor(maxDecisionsPerKey = 0) }
        assertFailsWith<IllegalArgumentException> { monitor.onApplied(" ", "x", at(1), at(1)) }
    }
}
