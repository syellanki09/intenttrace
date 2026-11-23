package io.intenttrace.systembenchmark

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/** Epoch microseconds, the time unit shared by Redis scores, the logs and the evaluator. */
object Micros {
    fun of(instant: Instant): Long = Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000L), instant.nano / 1_000L)

    /** Epoch nanoseconds, for comparisons that microseconds would tie. */
    fun nanosOf(instant: Instant): Long = Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000_000L), instant.nano.toLong())

    fun toInstant(micros: Long): Instant =
        Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)
}

/** A context update as it travels through Kafka. The occurrence time is also its primary commit time. */
data class ContextEvent(val eventId: String, val user: Int, val version: Long, val occurredMicros: Long, val payload: String) {
    init {
        require('|' !in eventId && '|' !in payload) { "Event fields must not contain the separator" }
    }

    fun encode(): String = "$eventId|$user|$version|$occurredMicros|$payload"

    companion object {
        fun decode(value: String): ContextEvent {
            val parts = value.split('|')
            require(parts.size == 5) { "Malformed event record" }
            return ContextEvent(parts[0], parts[1].toInt(), parts[2].toLong(), parts[3].toLong(), parts[4])
        }
    }
}

fun contextKey(user: Int): String = "u$user"
fun primaryKey(user: Int): String = "primary:${contextKey(user)}"
fun cacheKey(user: Int): String = "cache:${contextKey(user)}"

fun sha256Hex(digest: MessageDigest, value: String): String {
    val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
    val out = CharArray(bytes.size * 2)
    val hex = "0123456789abcdef"
    for (i in bytes.indices) {
        val b = bytes[i].toInt() and 0xff
        out[i * 2] = hex[b ushr 4]
        out[i * 2 + 1] = hex[b and 0x0f]
    }
    return String(out)
}

/** A JSONL log owned by one thread. Every thread writes its own file, so no locking is needed. */
class JsonlLog(path: Path, bufferChars: Int = 1 shl 20) : AutoCloseable {
    private val writer: BufferedWriter = Files.newBufferedWriter(path).let { BufferedWriter(it, bufferChars) }

    fun write(vararg fields: Pair<String, Any?>) {
        writer.write(Json.encode(linkedMapOf(*fields)))
        writer.write('\n'.code)
    }

    override fun close() = writer.close()
}

/** The run's logs, so that every one is flushed and closed at the end, whichever thread opened it. */
class LogRegistry(private val directory: Path, private val bufferChars: Int = 1 shl 20) : AutoCloseable {
    private val logs = ArrayList<JsonlLog>()

    fun open(name: String): JsonlLog = JsonlLog(directory.resolve(name), bufferChars).also { synchronized(logs) { logs += it } }

    val size: Int get() = synchronized(logs) { logs.size }

    override fun close() = synchronized(logs) { logs.forEach { it.close() } }
}

/** A growable array of primitive longs, for latency samples. */
class LongList(initial: Int = 1 shl 14) {
    private var values = LongArray(initial)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun addAll(other: LongList) {
        for (i in 0 until other.size) add(other.values[i])
    }

    fun sorted(): LongArray = values.copyOf(size).also { it.sort() }
}

/** Nearest-rank percentiles of [sorted], in microseconds when the samples are nanoseconds. */
fun percentiles(sorted: LongArray, divisor: Double = 1_000.0): Map<String, Any?> {
    if (sorted.isEmpty()) return mapOf("count" to 0)
    fun at(p: Double): Double {
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1] / divisor
    }
    return linkedMapOf(
        "count" to sorted.size,
        "p50" to at(50.0), "p90" to at(90.0), "p99" to at(99.0), "p999" to at(99.9),
        "max" to sorted.last() / divisor,
        "mean" to sorted.average() / divisor
    )
}

/** Minimal JSON encoder for maps, lists, strings, numbers, booleans and null. */
object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(value)
            is Double -> {
                require(value.isFinite()) { "JSON numbers must be finite" }
                out.append(Math.round(value * 1000.0) / 1000.0)
            }
            is Float -> write(out, value.toDouble())
            is Number -> out.append(value)
            is Enum<*> -> string(out, value.name)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    string(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Cannot encode ${value::class.simpleName}")
        }
    }

    private fun string(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
