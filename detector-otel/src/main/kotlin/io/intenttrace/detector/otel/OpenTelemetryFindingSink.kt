package io.intenttrace.detector.otel

import io.intenttrace.detector.Finding
import io.intenttrace.detector.FindingSink
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.trace.Span

/** Attribute names used for findings. They are IntentTrace's own, not OpenTelemetry semantic conventions. */
object IntentTraceAttributes {
    val FAILURE_CLASS: AttributeKey<String> = AttributeKey.stringKey("intenttrace.failure_class")
    val CONFIDENCE: AttributeKey<String> = AttributeKey.stringKey("intenttrace.confidence")
    val CONTEXT_KEY: AttributeKey<String> = AttributeKey.stringKey("intenttrace.context_key")
    val DECISION_ID: AttributeKey<String> = AttributeKey.stringKey("intenttrace.decision_id")
    val EVENT_ID: AttributeKey<String> = AttributeKey.stringKey("intenttrace.event_id")
    val EXPLANATION: AttributeKey<String> = AttributeKey.stringKey("intenttrace.explanation")
    const val EVIDENCE_PREFIX = "intenttrace.evidence."
}

/**
 * Publishes findings through OpenTelemetry, so they appear in the tracing and
 * metrics backends a team already runs.
 *
 * - Metric `intenttrace.findings` counts findings by failure class and
 *   confidence only. Context keys and identifiers never become metric
 *   attributes, to keep cardinality bounded.
 * - When a span is current and valid, the finding is also added to it as an
 *   `intenttrace.finding` event carrying the context key, identifiers,
 *   explanation and evidence, so an engineer can see why a request was wrong.
 */
class OpenTelemetryFindingSink(
    openTelemetry: OpenTelemetry,
    private val recordSpanEvents: Boolean = true
) : FindingSink {
    private val counter: LongCounter = openTelemetry
        .meterBuilder(INSTRUMENTATION_SCOPE)
        .build()
        .counterBuilder(METRIC_NAME)
        .setDescription("Information-integrity findings reported by the IntentTrace detector")
        .setUnit("{finding}")
        .build()

    override fun accept(finding: Finding) {
        counter.add(1, Attributes.of(
            IntentTraceAttributes.FAILURE_CLASS, finding.failureClass.id,
            IntentTraceAttributes.CONFIDENCE, finding.confidence.name
        ))
        if (!recordSpanEvents) return
        val span = Span.current()
        if (!span.spanContext.isValid) return
        val attributes = Attributes.builder()
            .put(IntentTraceAttributes.FAILURE_CLASS, finding.failureClass.id)
            .put(IntentTraceAttributes.CONFIDENCE, finding.confidence.name)
            .put(IntentTraceAttributes.CONTEXT_KEY, finding.contextKey)
            .put(IntentTraceAttributes.EXPLANATION, finding.explanation)
        finding.decisionId?.let { attributes.put(IntentTraceAttributes.DECISION_ID, it) }
        finding.eventId?.let { attributes.put(IntentTraceAttributes.EVENT_ID, it) }
        finding.evidence.forEach { (key, value) -> attributes.put(IntentTraceAttributes.EVIDENCE_PREFIX + key, value) }
        span.addEvent(EVENT_NAME, attributes.build(), finding.detectedAt)
    }

    companion object {
        const val INSTRUMENTATION_SCOPE = "io.intenttrace.detector"
        const val METRIC_NAME = "intenttrace.findings"
        const val EVENT_NAME = "intenttrace.finding"
    }
}
