package io.intenttrace.benchmarks

import io.intenttrace.evaluation.InvariantStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ContractUnitDriftScenarioTest {
    @Test
    fun `parsed wire payloads produce compatible upgrades and silent semantic drift`() {
        val experiments = ContractUnitDriftScenario().run()
        assertEquals(listOf(InvariantStatus.SATISFIED, InvariantStatus.VIOLATED, InvariantStatus.VIOLATED),
            experiments.map { it.evaluation.status })
        assertEquals(listOf("durationMillis:2000", "durationMillis:2000000", "durationMillis:2000000"),
            experiments.map { it.evaluation.observed.outcome })
        assertEquals(listOf(2L, 2000L, 2000L), experiments.map {
            Json.decodeFromString<DurationWirePayload>(it.wirePayload).duration
        })
        experiments.forEach {
            assertEquals("durationMillis:2000", it.evaluation.reference.outcome)
            assertNull(it.evaluation.detected)
            assertEquals("contract-incompatibility", it.evaluation.failureClass.id)
        }
        repeat(3) { assertEquals(experiments, ContractUnitDriftScenario().run()) }
    }

    @Test
    fun `another controlled duration is interpreted rather than hard-coded`() {
        val experiments = ContractUnitDriftScenario().run(5000)
        assertEquals(listOf("durationMillis:5000", "durationMillis:5000000", "durationMillis:5000000"),
            experiments.map { it.evaluation.observed.outcome })
    }

    @Test
    fun `zero duration does not falsely prove an observable mismatch`() {
        assertEquals(listOf(InvariantStatus.SATISFIED, InvariantStatus.SATISFIED, InvariantStatus.SATISFIED),
            ContractUnitDriftScenario().run(0).map { it.evaluation.status })
    }

    @Test
    fun `invalid durations and lossy or overflowing conversions fail visibly`() {
        assertFailsWith<IllegalArgumentException> { ContractUnitDriftScenario().run(-1) }
        assertFailsWith<IllegalArgumentException> { ContractUnitDriftScenario().run(1001) }
        val wire = DurationProducer(1, DurationUnit.MILLISECONDS).encode("input", Long.MAX_VALUE)
        assertFailsWith<ArithmeticException> { DurationConsumer("mobile", 2, DurationUnit.SECONDS).decide(wire) }
        assertFailsWith<IllegalArgumentException> { DurationProducer(0, DurationUnit.SECONDS) }
        assertFailsWith<IllegalArgumentException> { DurationConsumer("", 1, DurationUnit.SECONDS) }
    }
}
