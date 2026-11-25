package io.intenttrace.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MissingContextInvariantTest {
    private val required = ContextSnapshot(4, "gold")
    private val degraded = Degradation("context-source-empty", "tier-service")

    @Test fun `delivered context used as delivered is satisfied`() {
        val result = MissingContextInvariant().evaluate(required, required, "gold", null)
        assertEquals(InvariantStatus.SATISFIED, result.status)
        assertEquals(FailureClass.MISSING_CONTEXT, result.failureClass)
        assertNull(result.detected)
    }

    @Test fun `silent default violates and recorded degradation satisfies`() {
        for (detected in listOf(null, false, true)) {
            val result = MissingContextInvariant().evaluate(required, null, "standard", null, detected)
            assertEquals(InvariantStatus.VIOLATED, result.status)
            assertEquals(detected, result.detected)
        }
        assertEquals(InvariantStatus.SATISFIED,
            MissingContextInvariant().evaluate(required, null, "standard", degraded).status)
    }

    @Test fun `nothing required and delivered value ignored are classified`() {
        assertEquals(InvariantStatus.NOT_APPLICABLE,
            MissingContextInvariant().evaluate(null, null, "standard", null).status)
        assertEquals(InvariantStatus.VIOLATED,
            MissingContextInvariant().evaluate(required, required, "standard", null).status)
    }

    @Test fun `out of scope or malformed evidence fails immediately`() {
        assertFailsWith<IllegalArgumentException> { ContextSnapshot(0, "gold") }
        assertFailsWith<IllegalArgumentException> { ContextSnapshot(1, " ") }
        assertFailsWith<IllegalArgumentException> { Degradation("", "tier-service") }
        assertFailsWith<IllegalArgumentException> {
            MissingContextInvariant().evaluate(required, ContextSnapshot(3, "silver"), "silver", null)
        }
        assertFailsWith<IllegalArgumentException> {
            MissingContextInvariant().evaluate(required, required, "gold", degraded)
        }
        assertFailsWith<IllegalArgumentException> { MissingContextInvariant().evaluate(required, null, "", degraded) }
    }
}
