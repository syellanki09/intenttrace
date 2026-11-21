package io.intenttrace.detector

/**
 * Writes each finding as one compact JSON object per line (schema version 1).
 *
 * Keys appear in a fixed order and evidence is sorted, so identical findings
 * always produce identical bytes. The caller owns [out] and its flushing.
 */
class JsonLinesFindingSink(private val out: Appendable) : FindingSink {
    @Synchronized
    override fun accept(finding: Finding) {
        out.append(encode(finding)).append('\n')
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun encode(finding: Finding): String = buildString {
            append("{\"schemaVersion\":").append(SCHEMA_VERSION)
            field("failureClassId", finding.failureClass.id)
            field("confidence", finding.confidence.name)
            field("contextKey", finding.contextKey)
            field("decisionId", finding.decisionId)
            field("eventId", finding.eventId)
            field("detectedAt", finding.detectedAt.toString())
            field("explanation", finding.explanation)
            append(",\"evidence\":{")
            finding.evidence.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                string(key)
                append(':')
                string(value)
            }
            append("}}")
        }

        private fun StringBuilder.field(name: String, value: String?) {
            append(",\"").append(name).append("\":")
            if (value == null) append("null") else string(value)
        }

        private fun StringBuilder.string(value: String) {
            append('"')
            value.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
