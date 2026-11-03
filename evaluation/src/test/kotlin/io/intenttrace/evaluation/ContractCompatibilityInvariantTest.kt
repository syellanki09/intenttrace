package io.intenttrace.evaluation

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ContractCompatibilityInvariantTest {
    private val evaluator = ContractCompatibilityInvariant()
    private val reference = ClientContractDecision("input-001", "web", 1, "travel")

    @Test
    fun `versioned fixtures distinguish compatible upgrades from semantic drift`() {
        for (name in listOf("compatible-version-upgrade", "unit-drift", "same-version-semantic-drift")) {
            val fixture = Properties().apply {
                requireNotNull(ContractCompatibilityInvariantTest::class.java.getResourceAsStream("/contracts/$name.properties"))
                    .use { load(it) }
            }
            fun decision(prefix: String) = ClientContractDecision(
                fixture.getProperty("inputId"), fixture.getProperty("$prefix.clientId"),
                fixture.getProperty("$prefix.contractVersion").toLong(), fixture.getProperty("$prefix.outcome")
            )
            val control = decision("reference")
            val observed = decision("observed")
            val result = evaluator.evaluate(control, observed)
            assertEquals(InvariantStatus.valueOf(fixture.getProperty("expectedStatus")), result.status)
            assertEquals(FailureClass.CONTRACT_INCOMPATIBILITY, result.failureClass)
            assertEquals(control, result.reference)
            assertEquals(observed, result.observed)
            assertNull(result.detected)
            repeat(3) { assertEquals(result, evaluator.evaluate(control, observed)) }
        }
    }

    @Test
    fun `detection observations cannot change outcome comparison`() {
        for (outcome in listOf("travel", "technology")) {
            val observed = reference.copy(clientId = "mobile", contractVersion = 2, outcome = outcome)
            val baseline = evaluator.evaluate(reference, observed)
            for (signal in listOf(true, false)) {
                assertEquals(baseline.copy(detected = signal), evaluator.evaluate(reference, observed, signal))
            }
        }
    }

    @Test
    fun `unrelated inputs and invalid decision metadata fail fast`() {
        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(reference, reference.copy(inputId = "another-input"))
        }
        assertFailsWith<IllegalArgumentException> { reference.copy(inputId = " ") }
        assertFailsWith<IllegalArgumentException> { reference.copy(clientId = "") }
        assertFailsWith<IllegalArgumentException> { reference.copy(contractVersion = 0) }
        assertFailsWith<IllegalArgumentException> { reference.copy(outcome = "") }
    }
}
