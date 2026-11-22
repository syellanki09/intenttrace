package io.intenttrace.detector

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayGuardTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Test fun `first delivery is applied without a finding`() {
        val result = ReplayGuard(Duration.ofMinutes(5)).onDelivery("user-1", "a", "sha:3", start)
        assertTrue(result.shouldApply)
        assertNull(result.finding)
    }

    @Test fun `repeated identity and payload is a confirmed duplicate`() {
        val guard = ReplayGuard(Duration.ofMinutes(5))
        guard.onDelivery("user-1", "a", "sha:3", start)
        val second = guard.onDelivery("user-1", "a", "sha:3", start.plusSeconds(5))
        val third = guard.onDelivery("user-1", "a", "sha:3", start.plusSeconds(6))
        assertEquals(DeliveryVerdict.SKIP_DUPLICATE, second.verdict)
        assertEquals(FailureClassId.DUPLICATE_REPLAY, second.finding!!.failureClass)
        assertEquals("2", second.finding!!.evidence["deliveryCount"])
        assertEquals("3", third.finding!!.evidence["deliveryCount"])
        assertEquals("a", second.finding!!.eventId)
    }

    @Test fun `reused identity with a different payload is a conflict, not a duplicate`() {
        val guard = ReplayGuard(Duration.ofMinutes(5))
        guard.onDelivery("user-1", "a", "sha:3", start)
        val result = guard.onDelivery("user-1", "a", "sha:9", start.plusSeconds(1))
        assertEquals(DeliveryVerdict.SKIP_CONFLICT, result.verdict)
        assertEquals(FailureClassId.CONFLICTING_VALUES, result.finding!!.failureClass)
        assertEquals("sha:3", result.finding!!.evidence["firstPayloadFingerprint"])
    }

    @Test fun `identities are scoped by context key`() {
        val guard = ReplayGuard(Duration.ofMinutes(5))
        guard.onDelivery("user-1", "a", "sha:3", start)
        assertTrue(guard.onDelivery("user-2", "a", "sha:3", start).shouldApply)
    }

    @Test fun `retention and capacity bound memory and are reported as evictions`() {
        val byTime = ReplayGuard(Duration.ofSeconds(10))
        byTime.onDelivery("user-1", "a", "sha:3", start)
        assertTrue(byTime.onDelivery("user-1", "a", "sha:3", start.plusSeconds(11)).shouldApply)
        assertEquals(1, byTime.evictedIdentities)

        val bySize = ReplayGuard(Duration.ofHours(1), maxTrackedIdentities = 2)
        listOf("a", "b", "c").forEach { bySize.onDelivery("user-1", it, "sha", start) }
        assertEquals(2, bySize.trackedIdentities)
        assertEquals(1, bySize.evictedIdentities)
        assertTrue(bySize.onDelivery("user-1", "a", "sha", start).shouldApply)
        assertEquals(DeliveryVerdict.SKIP_DUPLICATE, bySize.onDelivery("user-1", "c", "sha", start).verdict)
    }

    @Test fun `a delivery whose application failed is applied again on retry`() {
        val guard = ReplayGuard(Duration.ofMinutes(5))
        assertTrue(guard.onDelivery("user-1", "a", "sha:3", start).shouldApply)
        assertTrue(guard.onApplicationFailed("user-1", "a", "sha:3"))
        val retry = guard.onDelivery("user-1", "a", "sha:3", start.plusSeconds(1))
        assertEquals(DeliveryVerdict.APPLY, retry.verdict)
        assertNull(retry.finding)
        assertEquals(DeliveryVerdict.SKIP_DUPLICATE, guard.onDelivery("user-1", "a", "sha:3", start.plusSeconds(2)).verdict)
    }

    @Test fun `a failed application only releases the payload that was recorded`() {
        val guard = ReplayGuard(Duration.ofMinutes(5))
        guard.onDelivery("user-1", "a", "sha:3", start)
        assertFalse(guard.onApplicationFailed("user-1", "a", "sha:9"))
        assertFalse(guard.onApplicationFailed("user-1", "unknown", "sha:3"))
        assertEquals(DeliveryVerdict.SKIP_DUPLICATE, guard.onDelivery("user-1", "a", "sha:3", start.plusSeconds(1)).verdict)
        assertEquals(1, guard.trackedIdentities)
    }

    @Test fun `retention expires identities whose deliveries arrived out of timestamp order`() {
        val guard = ReplayGuard(Duration.ofSeconds(10))
        guard.onDelivery("user-1", "a", "sha", start.plusSeconds(10))
        guard.onDelivery("user-1", "b", "sha", start.plusSeconds(9))
        val late = guard.onDelivery("user-1", "b", "sha", start.plusMillis(19_500))
        assertEquals(DeliveryVerdict.APPLY, late.verdict, "b was first delivered 10.5 seconds earlier")
        assertEquals(DeliveryVerdict.SKIP_DUPLICATE, guard.onDelivery("user-1", "a", "sha", start.plusMillis(19_600)).verdict)
        assertEquals(1, guard.evictedIdentities)
    }

    @Test fun `time-based expiry still works after many out-of-order deliveries`() {
        val guard = ReplayGuard(Duration.ofSeconds(10), maxTrackedIdentities = 4)
        (0 until 40).forEach { guard.onDelivery("user-1", "e$it", "sha", start.plusSeconds((it % 7).toLong())) }
        assertEquals(4, guard.trackedIdentities)
        guard.onDelivery("user-1", "fresh", "sha", start.plusSeconds(60))
        assertEquals(1, guard.trackedIdentities)
    }

    @Test fun `invalid configuration and blank identities are rejected`() {
        assertFailsWith<IllegalArgumentException> { ReplayGuard(Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { ReplayGuard(Duration.ofSeconds(1), 0) }
        assertFailsWith<IllegalArgumentException> { ReplayGuard(Duration.ofSeconds(1)).onDelivery("k", " ", "f", start) }
    }
}
