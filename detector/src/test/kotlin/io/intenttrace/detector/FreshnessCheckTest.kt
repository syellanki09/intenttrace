package io.intenttrace.detector

import io.intenttrace.evaluation.FailureClass
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FreshnessCheckTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val decidedAt = start.plusSeconds(60)
    private val v1 = VersionStamp(1, start.plusSeconds(10))
    private val v2 = VersionStamp(2, start.plusSeconds(20))
    private val v3 = VersionStamp(3, start.plusSeconds(30))

    private fun oracle(latest: VersionStamp?) = LatestVersionOracle { _, _ -> latest }
    private fun read(used: VersionStamp?) = ContextRead("decision-1", "user-1", decidedAt, used)

    @Test fun `failure class identifiers match the evaluation taxonomy`() {
        assertEquals(FailureClass.entries.map { it.name to it.id }, FailureClassId.entries.map { it.name to it.id })
    }

    @Test fun `latest eligible read produces no finding`() {
        assertTrue(FreshnessCheck(oracle(v2)).check(read(v2), decidedAt).isEmpty())
    }

    @Test fun `older read is a confirmed stale-context finding with version evidence`() {
        val finding = FreshnessCheck(oracle(v2)).check(read(v1), decidedAt).single()
        assertEquals(FailureClassId.STALE_CONTEXT, finding.failureClass)
        assertEquals(Confidence.CONFIRMED, finding.confidence)
        assertEquals("decision-1", finding.decisionId)
        assertEquals("1", finding.evidence["usedVersion"])
        assertEquals("2", finding.evidence["latestEligibleVersion"])
        assertEquals("1", finding.evidence["versionLag"])
        assertEquals("PT40S", finding.evidence["newerVersionVisibleFor"])
    }

    @Test fun `version lag within tolerance is accepted`() {
        val check = FreshnessCheck(oracle(v3), maxVersionLag = 1)
        assertTrue(check.check(read(v2), decidedAt).isEmpty())
        assertEquals("2", check.check(read(v1), decidedAt).single().evidence["versionLag"])
    }

    @Test fun `no read while context is eligible is a missing-context finding`() {
        val finding = FreshnessCheck(oracle(v2)).check(read(null), decidedAt).single()
        assertEquals(FailureClassId.MISSING_CONTEXT, finding.failureClass)
        assertEquals("2", finding.evidence["latestEligibleVersion"])
    }

    @Test fun `no read and no eligible context is a legitimate default`() {
        assertTrue(FreshnessCheck(oracle(null)).check(read(null), decidedAt).isEmpty())
    }

    @Test fun `read newer than the oracle reports a suspected disagreement`() {
        val finding = FreshnessCheck(oracle(v1)).check(read(v2), decidedAt).single()
        assertEquals(FailureClassId.CONFLICTING_VALUES, finding.failureClass)
        assertEquals(Confidence.SUSPECTED, finding.confidence)
    }

    @Test fun `age threshold applies only without an oracle verdict`() {
        val ageOnly = FreshnessCheck(maxAge = Duration.ofSeconds(45))
        val finding = ageOnly.check(read(v1), decidedAt).single()
        assertEquals(FailureClassId.STALE_CONTEXT, finding.failureClass)
        assertEquals(Confidence.SUSPECTED, finding.confidence)
        assertEquals("PT50S", finding.evidence["age"])
        assertTrue(ageOnly.check(read(v3), decidedAt).isEmpty())
        // An authoritative answer that the read is current overrides the age heuristic.
        assertTrue(FreshnessCheck(oracle(v1), maxAge = Duration.ofSeconds(45)).check(read(v1), decidedAt).isEmpty())
        // An oracle that does not know the key falls back to the heuristic.
        assertEquals(Confidence.SUSPECTED,
            FreshnessCheck(oracle(null), maxAge = Duration.ofSeconds(45)).check(read(v1), decidedAt).single().confidence)
    }

    @Test fun `invalid configuration and impossible evidence are rejected`() {
        assertFailsWith<IllegalArgumentException> { FreshnessCheck() }
        assertFailsWith<IllegalArgumentException> { FreshnessCheck(oracle(v1), maxVersionLag = -1) }
        assertFailsWith<IllegalArgumentException> { FreshnessCheck(maxAge = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { VersionStamp(0, start) }
        assertFailsWith<IllegalArgumentException> { ContextRead(" ", "k", start, v1) }
    }

    @Test fun `timestamps after the decision are suspected disagreements, not host failures`() {
        val future = VersionStamp(4, decidedAt.plusSeconds(1))
        val fromOracle = FreshnessCheck(oracle(future)).check(read(v1), decidedAt).single()
        assertEquals(FailureClassId.CONFLICTING_VALUES, fromOracle.failureClass)
        assertEquals("oracle", fromOracle.evidence["source"])
        val fromRead = FreshnessCheck(oracle(v1)).check(read(future), decidedAt).single()
        assertEquals(Confidence.SUSPECTED, fromRead.confidence)
        assertEquals("read", fromRead.evidence["source"])
    }
}
