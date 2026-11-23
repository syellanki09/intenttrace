package example

import io.intenttrace.detector.CollectingFindingSink
import io.intenttrace.detector.FailureClassId
import io.intenttrace.detector.otel.IntentTraceAttributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class RecommendationServiceTest {
    private val start = Instant.parse("2026-01-01T09:00:00Z")

    @Test fun `a missed cache update and a redelivery are both reported`() {
        val spans = InMemorySpanExporter.create()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .build()
        val findings = CollectingFindingSink()
        val service = RecommendationService(openTelemetry, Clock.fixed(start.plusSeconds(60), ZoneOffset.UTC), listOf(findings))

        val first = ProfileUpdate("evt-1", Profile("user-42", 1, start, listOf("hiking")), "sha256:a1")
        val second = ProfileUpdate("evt-2", Profile("user-42", 2, start.plusSeconds(10), listOf("hiking", "travel")), "sha256:b2")
        service.onUpdate(first)
        service.onUpdate(second, applyToCache = false)
        service.onUpdate(second)
        val picks = service.recommend("request-1", "user-42", start.plusSeconds(30))

        assertEquals(listOf("top picks in hiking"), picks, "the cache still serves version 1")
        assertEquals(listOf(FailureClassId.DUPLICATE_REPLAY, FailureClassId.STALE_CONTEXT),
            findings.findings().map { it.failureClass })
        val event = spans.finishedSpanItems.single().events.single()
        assertEquals("stale-context", event.attributes.get(IntentTraceAttributes.FAILURE_CLASS))
        openTelemetry.close()
    }

    @Test fun `a fresh read is quiet`() {
        val findings = CollectingFindingSink()
        val service = RecommendationService(io.opentelemetry.api.OpenTelemetry.noop(),
            Clock.fixed(start.plusSeconds(60), ZoneOffset.UTC), listOf(findings))
        service.onUpdate(ProfileUpdate("evt-1", Profile("user-7", 1, start, listOf("music")), "sha256:c3"))
        assertEquals(listOf("top picks in music"), service.recommend("request-2", "user-7", start.plusSeconds(5)))
        assertEquals(emptyList(), findings.findings())
    }
}
