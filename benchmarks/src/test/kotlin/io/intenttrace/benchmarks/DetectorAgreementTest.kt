package io.intenttrace.benchmarks

import io.intenttrace.evaluation.InvariantStatus
import io.intenttrace.provenance.ReproductionMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DetectorAgreementTest {
    private fun metadata(name: String) = ReproductionMetadata(name, "1", "a".repeat(40), null,
        "test runtime", "17", "test OS", mapOf("sourceDirty" to "false"))

    private fun records() = detectorAgreementRecords(StaleContextScenario().run(metadata("stale-context")),
        replayRecords(metadata("duplicate-replay")), eventTimeRecords(metadata("event-time-ordering")), "a".repeat(40))

    @Test fun `runtime detector agrees with harness ground truth on every covered fixture`() {
        val expected = listOf(
            listOf("stale-context", "control", "SATISFIED", "", "true-negative"),
            listOf("stale-context", "injected", "VIOLATED", "stale-context", "true-positive"),
            listOf("duplicate-replay", "control", "SATISFIED", "", "true-negative"),
            listOf("duplicate-replay", "naive-replay", "VIOLATED", "duplicate-replay", "true-positive"),
            listOf("duplicate-replay", "idempotent-replay", "SATISFIED", "duplicate-replay", "detected-and-prevented"),
            listOf("event-time-ordering", "control", "SATISFIED", "", "true-negative"),
            listOf("event-time-ordering", "reordered-arrival", "VIOLATED", "stale-context", "true-positive"),
            listOf("event-time-ordering", "event-time-reconciliation", "SATISFIED", "", "true-negative"),
            listOf("event-time-ordering", "late-arrival", "VIOLATED", "delayed-events", "true-positive")
        )
        assertEquals(expected, records().map {
            listOf(it.family, it.caseId, it.groundTruthStatus.name, it.findingClassIds.joinToString(","), it.outcome)
        })
    }

    @Test fun `agreement is deterministic`() {
        repeat(3) { assertEquals(records(), records()) }
    }

    @Test fun `outcome labels cover every combination`() {
        assertEquals("false-negative", agreementOutcome(InvariantStatus.VIOLATED, false, false))
        assertEquals("false-positive", agreementOutcome(InvariantStatus.SATISFIED, true, false))
        assertEquals("true-negative", agreementOutcome(InvariantStatus.NOT_APPLICABLE, false, false))
    }

    @Test fun `runner rejects unsupported arguments`() {
        DetectorAgreementMain.main(arrayOf("--help"))
        for (args in listOf(arrayOf("--unknown", "x"), arrayOf("--output"))) {
            assertFailsWith<IllegalArgumentException> { DetectorAgreementMain.main(args) }
        }
    }
}
