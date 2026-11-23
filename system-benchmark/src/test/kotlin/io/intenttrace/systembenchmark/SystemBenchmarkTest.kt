package io.intenttrace.systembenchmark

import java.nio.file.Path
import java.time.Instant
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SystemBenchmarkTest {
    private fun config(seed: Long = 7) = BenchmarkConfig(
        DetectorMode.OFF, Path.of("unused"), "test", seed = seed, users = 1_000,
        eventsPerSecond = 2_000, decisionsPerSecond = 1_000, warmupSeconds = 1, measureSeconds = 4, saturationSeconds = 1
    )

    @Test
    fun `the same seed gives the same workload`() {
        val first = Workload.generate(config())
        val second = Workload.generate(config())
        assertEquals(first.events, second.events)
        assertEquals(first.decisions, second.decisions)
        assertEquals(first.evictions, second.evictions)
        val other = Workload.generate(config(seed = 8))
        assertTrue(first.events != other.events)
    }

    @Test
    fun `rates, windows and fault mix follow the configuration`() {
        val workload = Workload.generate(config())
        // 6 seconds at 2,000 events per second; fixed-rate decisions stop before saturation.
        assertTrue(workload.events.size in 11_400..12_600, "events: ${workload.events.size}")
        assertTrue(workload.decisions.size in 4_700..5_300, "decisions: ${workload.decisions.size}")
        assertTrue(workload.decisions.all { it.atNanos < 5_000_000_000L })
        assertTrue(workload.events.zipWithNext().all { (a, b) -> a.atNanos <= b.atNanos })
        val faults = workload.events.groupingBy { it.fault }.eachCount()
        assertTrue((faults[SendFault.DELAYED] ?: 0) in 70..180, "delayed: $faults")
        assertTrue((faults[SendFault.DUPLICATE] ?: 0) in 70..180, "duplicates: $faults")
        assertTrue((faults[SendFault.CONFLICTING] ?: 0) in 2..30, "conflicting: $faults")
    }

    @Test
    fun `zipf sampling favours low ranks and stays in range`() {
        val sampler = ZipfSampler(100, 1.0)
        val random = SplittableRandom(1)
        val counts = IntArray(100)
        repeat(100_000) { counts[sampler.sample(random)]++ }
        assertTrue(counts[0] > counts[9] && counts[9] > counts[99], counts.take(10).toString())
        // For s = 1 the most popular of 100 items gets about 1 / H(100) = 19% of draws.
        assertTrue(counts[0] in 17_000..21_500, "top rank: ${counts[0]}")
        assertEquals(0, ZipfSampler(1, 0.9).sample(random))
    }

    @Test
    fun `events survive the wire format and reject the separator`() {
        val event = ContextEvent("e12", 3, 9, 1_700_000_000_123_456, "p3.9.abc")
        assertEquals(event, ContextEvent.decode(event.encode()))
        assertFailsWith<IllegalArgumentException> { ContextEvent("e|1", 1, 1, 1, "p") }
        assertFailsWith<IllegalArgumentException> { ContextEvent.decode("e1|2|3") }
    }

    @Test
    fun `microseconds round trip, including before the epoch`() {
        val instant = Instant.parse("2026-11-23T18:00:00.123456Z")
        assertEquals(instant, Micros.toInstant(Micros.of(instant)))
        val early = Instant.parse("1969-12-31T23:59:59.999999Z")
        assertEquals(-1, Micros.of(early))
        assertEquals(early, Micros.toInstant(-1))
    }

    @Test
    fun `lost writes hit about the configured share of events`() {
        val lost = (0 until 100_000).count { isLostWrite("e$it", 5) }
        assertTrue(lost in 350..650, "lost: $lost")
        assertEquals(0, (0 until 1_000).count { isLostWrite("e$it", 0) })
    }

    @Test
    fun `percentiles use nearest rank`() {
        val samples = LongArray(100) { (it + 1) * 1_000L }
        val result = percentiles(samples)
        assertEquals(100, result["count"])
        assertEquals(50.0, result["p50"])
        assertEquals(99.0, result["p99"])
        assertEquals(100.0, result["p999"])
        assertEquals(mapOf("count" to 0), percentiles(LongArray(0)))
    }

    @Test
    fun `json encoding escapes strings and keeps order`() {
        val encoded = Json.encode(linkedMapOf("b" to "say \"hi\"\n", "a" to listOf(1, null, true), "c" to 0.12345))
        assertEquals("{\"b\":\"say \\\"hi\\\"\\n\",\"a\":[1,null,true],\"c\":0.123}", encoded)
        assertFailsWith<IllegalArgumentException> { Json.encode(Double.NaN) }
    }

    @Test
    fun `arguments are parsed strictly`() {
        val parsed = BenchmarkConfig.parse(arrayOf("--mode=on-async", "--output=out", "--run-id=r1", "--users=50"))
        assertEquals(DetectorMode.ON_ASYNC, parsed.mode)
        assertEquals(50, parsed.users)
        assertEquals("context-updates-r1", parsed.topic)
        assertFailsWith<IllegalArgumentException> { BenchmarkConfig.parse(arrayOf("--mode=on", "--output=out")) }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkConfig.parse(arrayOf("--mode=on", "--output=out", "--run-id=r1", "--colour=blue"))
        }
        assertFailsWith<IllegalArgumentException> { BenchmarkConfig.parse(arrayOf("--mode=sometimes", "--output=o", "--run-id=r")) }
        assertFailsWith<IllegalArgumentException> { BenchmarkConfig.parse(arrayOf("--mode=on", "--output=o", "--run-id=../x")) }
    }
}
