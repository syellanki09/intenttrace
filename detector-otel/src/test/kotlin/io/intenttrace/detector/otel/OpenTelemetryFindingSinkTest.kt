package io.intenttrace.detector.otel

import io.intenttrace.detector.Confidence
import io.intenttrace.detector.FailureClassId
import io.intenttrace.detector.Finding
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenTelemetryFindingSinkTest {
    private val metrics = InMemoryMetricReader.create()
    private val spans = InMemorySpanExporter.create()
    private val sdk = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .build()
    private val at = Instant.parse("2026-01-01T00:01:00Z")
    private val stale = Finding(FailureClassId.STALE_CONTEXT, Confidence.CONFIRMED, "user-1", at,
        "Decision used context version 1 instead of latest eligible version 2.", decisionId = "d1",
        evidence = mapOf("usedVersion" to "1", "latestEligibleVersion" to "2"))

    @AfterTest fun close() = sdk.close()

    @Test fun `counts findings by class and confidence without high-cardinality attributes`() {
        val sink = OpenTelemetryFindingSink(sdk)
        sink.accept(stale)
        sink.accept(stale)
        val metric = metrics.collectAllMetrics().single { it.name == OpenTelemetryFindingSink.METRIC_NAME }
        val point = metric.longSumData.points.single()
        assertEquals(2, point.value)
        assertEquals(mapOf("intenttrace.failure_class" to "stale-context", "intenttrace.confidence" to "CONFIRMED"),
            point.attributes.asMap().mapKeys { it.key.key })
    }

    @Test fun `adds a span event with evidence to the current span`() {
        val span = sdk.getTracer("test").spanBuilder("decide").startSpan()
        span.makeCurrent().use { OpenTelemetryFindingSink(sdk).accept(stale) }
        span.end()
        val event = spans.finishedSpanItems.single().events.single()
        assertEquals(OpenTelemetryFindingSink.EVENT_NAME, event.name)
        val attributes = event.attributes
        assertEquals("user-1", attributes.get(IntentTraceAttributes.CONTEXT_KEY))
        assertEquals("d1", attributes.get(IntentTraceAttributes.DECISION_ID))
        assertNull(attributes.get(IntentTraceAttributes.EVENT_ID))
        assertEquals("2", attributes.get(AttributeKey.stringKey("intenttrace.evidence.latestEligibleVersion")))
        assertEquals(at.toEpochMilli() * 1_000_000, event.epochNanos)
    }

    @Test fun `records only the metric when no span is current or events are disabled`() {
        OpenTelemetryFindingSink(sdk).accept(stale)
        val span = sdk.getTracer("test").spanBuilder("decide").startSpan()
        span.makeCurrent().use { OpenTelemetryFindingSink(sdk, recordSpanEvents = false).accept(stale) }
        span.end()
        assertTrue(spans.finishedSpanItems.single().events.isEmpty())
        assertEquals(2, metrics.collectAllMetrics().single().longSumData.points.single().value)
    }
}
