package io.intenttrace.detector

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntentTraceDetectorTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(start.plusSeconds(100), ZoneOffset.UTC)

    @Test fun `findings reach every sink and are counted by class`() {
        val first = CollectingFindingSink()
        val second = CollectingFindingSink()
        val detector = IntentTraceDetector(listOf(first, second),
            freshness = FreshnessCheck({ _, _ -> VersionStamp(2, start.plusSeconds(20)) }),
            replay = ReplayGuard(Duration.ofMinutes(5)), clock = clock)
        detector.checkDecision(ContextRead("d1", "user-1", start.plusSeconds(60), VersionStamp(1, start)))
        detector.onDelivery("user-1", "a", "sha", start)
        assertFalse(detector.onDelivery("user-1", "a", "sha", start.plusSeconds(1)).shouldApply)
        assertEquals(2, first.findings().size)
        assertEquals(first.findings(), second.findings())
        assertEquals(clock.instant(), first.findings().first().detectedAt)
        val counts = detector.findingCounts()
        assertEquals(1, counts[FailureClassId.STALE_CONTEXT])
        assertEquals(1, counts[FailureClassId.DUPLICATE_REPLAY])
        assertEquals(0, counts[FailureClassId.DELAYED_EVENTS])
    }

    @Test fun `a failing sink never breaks the host path`() {
        val healthy = CollectingFindingSink()
        val detector = IntentTraceDetector(listOf(FindingSink { error("backend down") }, healthy),
            replay = ReplayGuard(Duration.ofMinutes(5)), clock = clock)
        detector.onDelivery("user-1", "a", "sha", start)
        detector.onDelivery("user-1", "a", "sha", start)
        assertEquals(1, detector.sinkFailures)
        assertEquals(1, healthy.findings().size)
    }

    @Test fun `a failing oracle never breaks the host path`() {
        val sink = CollectingFindingSink()
        val detector = IntentTraceDetector(listOf(sink),
            freshness = FreshnessCheck({ _, _ -> throw IllegalStateException("primary unreachable") }), clock = clock)
        assertTrue(detector.checkDecision(ContextRead("d1", "user-1", start, VersionStamp(1, start))).isEmpty())
        assertEquals(1, detector.checkFailures)
        assertTrue(sink.findings().isEmpty())
    }

    @Test fun `decisions feed late-arrival detection`() {
        val sink = CollectingFindingSink()
        val detector = IntentTraceDetector(listOf(sink), eventTime = EventTimeMonitor(), clock = clock)
        detector.checkDecision(ContextRead("d1", "user-1", start.plusSeconds(40), null), reflectedOccurredAt = null)
        val findings = detector.onArrival("user-1", "b", start.plusSeconds(20), start.plusSeconds(45))
        assertEquals(FailureClassId.DELAYED_EVENTS, findings.single().failureClass)
        assertEquals(findings, sink.findings())
    }

    @Test fun `a failed application lets the retry through`() {
        val sink = CollectingFindingSink()
        val detector = IntentTraceDetector(listOf(sink), replay = ReplayGuard(Duration.ofMinutes(5)), clock = clock)
        assertTrue(detector.onDelivery("user-1", "a", "sha", start).shouldApply)
        assertTrue(detector.onApplicationFailed("user-1", "a", "sha"))
        assertTrue(detector.onDelivery("user-1", "a", "sha", start.plusSeconds(1)).shouldApply)
        assertTrue(sink.findings().isEmpty())
    }

    @Test fun `unconfigured checks are explicit errors`() {
        assertFailsWith<IllegalArgumentException> { IntentTraceDetector(emptyList()) }
        val detector = IntentTraceDetector(emptyList(), eventTime = EventTimeMonitor())
        assertFailsWith<IllegalStateException> { detector.onDelivery("k", "e", "f") }
        assertFailsWith<IllegalStateException> { detector.onApplicationFailed("k", "e", "f") }
        assertTrue(detector.checkDecision(ContextRead("d", "k", start, null)).isEmpty())
    }
}
