package example

import io.intenttrace.detector.ContextRead
import io.intenttrace.detector.DeliveryResult
import io.intenttrace.detector.FindingSink
import io.intenttrace.detector.FreshnessCheck
import io.intenttrace.detector.IntentTraceDetector
import io.intenttrace.detector.JsonLinesFindingSink
import io.intenttrace.detector.ReplayGuard
import io.intenttrace.detector.VersionStamp
import io.intenttrace.detector.otel.OpenTelemetryFindingSink
import io.opentelemetry.api.OpenTelemetry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A user's profile version as the service sees it. */
data class Profile(val userId: String, val version: Long, val committedAt: Instant, val interests: List<String>)

/** A profile update delivered by an event stream. */
data class ProfileUpdate(val eventId: String, val profile: Profile, val payloadSha256: String)

/**
 * A small recommendation service with the usual shape: the primary store is the
 * source of truth, a cache serves reads, and updates arrive from a stream that
 * may redeliver them. The detector watches both paths and reports what it sees
 * to OpenTelemetry and to [extraSinks].
 */
class RecommendationService(
    openTelemetry: OpenTelemetry,
    clock: Clock,
    extraSinks: List<FindingSink> = emptyList()
) {
    private val primary = mutableMapOf<String, Profile>()
    private val cache = mutableMapOf<String, Profile>()
    private val tracer = openTelemetry.getTracer("example.recommendations")

    val detector = IntentTraceDetector(
        sinks = listOf(OpenTelemetryFindingSink(openTelemetry)) + extraSinks,
        freshness = FreshnessCheck(oracle = { userId, at ->
            primary[userId]?.takeIf { !it.committedAt.isAfter(at) }?.let { VersionStamp(it.version, it.committedAt) }
        }),
        replay = ReplayGuard(retention = Duration.ofHours(1)),
        clock = clock
    )

    /** Applies a delivered update to the primary store, once per event. */
    fun onUpdate(update: ProfileUpdate, applyToCache: Boolean = true) {
        val delivery: DeliveryResult = detector.onDelivery(update.profile.userId, update.eventId, update.payloadSha256)
        if (!delivery.shouldApply) return
        try {
            primary[update.profile.userId] = update.profile
            if (applyToCache) cache[update.profile.userId] = update.profile
        } catch (failure: RuntimeException) {
            detector.onApplicationFailed(delivery)
            throw failure
        }
    }

    /** Serves recommendations from the cache and checks the context it used. */
    fun recommend(requestId: String, userId: String, now: Instant): List<String> {
        val span = tracer.spanBuilder("recommend").startSpan()
        return try {
            span.makeCurrent().use {
                val profile = cache[userId]
                detector.checkDecision(ContextRead(requestId, userId, now,
                    profile?.let { VersionStamp(it.version, it.committedAt) }))
                profile?.interests.orEmpty().map { "top picks in $it" }
            }
        } finally {
            span.end()
        }
    }
}

/** Runs a short scenario and prints every finding as one JSON line. */
fun main() {
    val start = Instant.parse("2026-01-01T09:00:00Z")
    val service = RecommendationService(OpenTelemetry.noop(), Clock.fixed(start.plusSeconds(60), ZoneOffset.UTC),
        extraSinks = listOf(JsonLinesFindingSink(System.out)))
    val first = ProfileUpdate("evt-1", Profile("user-42", 1, start, listOf("hiking")), "sha256:a1")
    val second = ProfileUpdate("evt-2", Profile("user-42", 2, start.plusSeconds(10), listOf("hiking", "travel")), "sha256:b2")
    service.onUpdate(first)
    service.onUpdate(second, applyToCache = false) // the cache missed this update
    service.onUpdate(second)                       // the stream redelivered it
    service.recommend("request-1", "user-42", start.plusSeconds(30))
    System.out.flush()
}
