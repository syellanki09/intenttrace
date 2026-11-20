package io.intenttrace.detector.otel

import io.intenttrace.detector.ContextRead
import io.intenttrace.detector.EventTimeMonitor
import io.intenttrace.detector.FreshnessCheck
import io.intenttrace.detector.IntentTraceDetector
import io.intenttrace.detector.ReplayGuard
import io.intenttrace.detector.VersionStamp
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Keeps the integration example in docs/detector.md compiling and behaving as described. */
class ExampleIntegrationTest {
    /** Stand-in for the host's authoritative store, for example a primary database. */
    private class PrimaryStore {
        val versions = mutableMapOf<String, VersionStamp>()
        fun latestVersion(key: String, at: Instant): VersionStamp? = versions[key]?.takeIf { !it.committedAt.isAfter(at) }
    }

    @Test fun `documented integration reports a stale read on the request span`() {
        val spans = InMemorySpanExporter.create()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .build()
        val primary = PrimaryStore()
        val now = Instant.parse("2026-01-01T00:01:00Z")
        primary.versions["user-42"] = VersionStamp(7, now.minusSeconds(30))

        // --- example start ---
        val detector = IntentTraceDetector(
            sinks = listOf(OpenTelemetryFindingSink(openTelemetry)),
            freshness = FreshnessCheck(oracle = { key, at -> primary.latestVersion(key, at) }),
            replay = ReplayGuard(retention = Duration.ofHours(1)),
            eventTime = EventTimeMonitor()
        )

        // In the decision path, after reading context from a cache or replica:
        val cachedVersion = VersionStamp(6, now.minusSeconds(90))
        val span = openTelemetry.getTracer("recommendations").spanBuilder("recommend").startSpan()
        span.makeCurrent().use {
            detector.checkDecision(ContextRead("request-1", "user-42", now, cachedVersion))
        }
        span.end()
        // --- example end ---

        val event = spans.finishedSpanItems.single().events.single()
        assertEquals("stale-context", event.attributes.get(IntentTraceAttributes.FAILURE_CLASS))
        assertEquals("1", event.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(
            "intenttrace.evidence.versionLag")))
        openTelemetry.close()
    }
}
