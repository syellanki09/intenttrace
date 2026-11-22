package io.intenttrace.detector

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonLinesFindingSinkTest {
    private val at = Instant.parse("2026-01-01T00:01:00Z")

    @Test fun `encodes a stable compact line with escaped text and sorted evidence`() {
        val finding = Finding(FailureClassId.STALE_CONTEXT, Confidence.CONFIRMED, "user \"1\"", at,
            "line\nbreak\\tab\t", decisionId = "d1",
            evidence = mapOf("z" to "last", "a" to "first\u0001"))
        val out = StringBuilder()
        JsonLinesFindingSink(out).accept(finding)
        assertEquals(
            "{\"schemaVersion\":1,\"failureClassId\":\"stale-context\",\"confidence\":\"CONFIRMED\"," +
                "\"contextKey\":\"user \\\"1\\\"\",\"decisionId\":\"d1\",\"eventId\":null," +
                "\"detectedAt\":\"2026-01-01T00:01:00Z\",\"explanation\":\"line\\nbreak\\\\tab\\t\"," +
                "\"evidence\":{\"a\":\"first\\u0001\",\"z\":\"last\"}}\n",
            out.toString()
        )
    }

    @Test fun `findings snapshot evidence and compare by value`() {
        val evidence = mutableMapOf("k" to "v")
        val finding = Finding(FailureClassId.DUPLICATE_REPLAY, Confidence.CONFIRMED, "key", at, "x", evidence = evidence)
        evidence["k"] = "changed"
        assertEquals("v", finding.evidence["k"])
        assertEquals(finding, Finding(FailureClassId.DUPLICATE_REPLAY, Confidence.CONFIRMED, "key", at, "x",
            evidence = mapOf("k" to "v")))
    }
}
