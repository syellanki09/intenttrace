package io.intenttrace.benchmarks

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunnerHelpTest {
    @Test
    fun `both help flags print the same supported options without executing a scenario`() {
        val original = System.out
        val outputs = mutableListOf<String>()
        try {
            for (flag in listOf("--help", "-h")) {
                val bytes = ByteArrayOutputStream()
                PrintStream(bytes).use { output ->
                    System.setOut(output)
                    main(arrayOf(flag))
                }
                outputs += bytes.toString(Charsets.UTF_8)
            }
        } finally {
            System.setOut(original)
        }
        assertEquals(outputs[0], outputs[1])
        for (option in listOf("--config", "--output", "--source-commit")) {
            assertTrue(outputs[0].contains(option))
        }
        assertTrue(!outputs[0].contains("Wrote 2 records"))
    }

    @Test
    fun `help mixed with execution arguments is rejected rather than silently ignored`() {
        assertFailsWith<IllegalArgumentException> { main(arrayOf("--help", "--output", "unused.jsonl")) }
    }
}
