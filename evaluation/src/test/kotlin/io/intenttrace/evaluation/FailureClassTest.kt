package io.intenttrace.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals

class FailureClassTest {
    @Test
    fun `failure classes retain all six unique canonical taxonomy ids`() {
        assertEquals(
            listOf("stale-context", "missing-context", "delayed-events",
                "duplicate-replay", "conflicting-values", "contract-incompatibility"),
            FailureClass.entries.map { it.id }
        )
        assertEquals(6, FailureClass.entries.map { it.id }.toSet().size)
    }
}
