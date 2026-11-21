package io.intenttrace.detector

import java.time.Duration
import java.time.Instant

/** What the host should do with one delivery. */
enum class DeliveryVerdict {
    APPLY,

    /** The same identity and payload were already delivered within retention. */
    SKIP_DUPLICATE,

    /** The same identity arrived with a different payload; neither copy is trustworthy by itself. */
    SKIP_CONFLICT
}

data class DeliveryResult(val verdict: DeliveryVerdict, val finding: Finding?) {
    val shouldApply: Boolean
        get() = verdict == DeliveryVerdict.APPLY
}

/**
 * Detects repeated and conflicting deliveries of the same logical event.
 *
 * The guard remembers each `(contextKey, eventId)` for [retention] after its
 * first delivery, up to [maxTrackedIdentities]. A host may follow the verdict
 * (idempotent processing) or only record the finding (observation mode).
 *
 * Memory is bounded on purpose: once an identity is evicted, a later replay of
 * it is not detected. [evictedIdentities] counts how often that window closed,
 * so a host can tell when to widen it.
 */
class ReplayGuard(
    private val retention: Duration,
    private val maxTrackedIdentities: Int = 100_000
) {
    private data class Identity(val contextKey: String, val eventId: String)
    private class Seen(val fingerprint: String, val firstDeliveredAt: Instant, var deliveries: Int)

    private val seen = LinkedHashMap<Identity, Seen>()
    private var evicted = 0L

    init {
        require(!retention.isNegative && !retention.isZero) { "Retention must be positive" }
        require(maxTrackedIdentities > 0) { "At least one identity must be tracked" }
    }

    /**
     * Records one delivery. [payloadFingerprint] is any stable digest of the
     * payload, so that a reused identity with different content is reported as
     * a conflict rather than silently treated as a duplicate.
     */
    @Synchronized
    fun onDelivery(
        contextKey: String,
        eventId: String,
        payloadFingerprint: String,
        deliveredAt: Instant,
        detectedAt: Instant = deliveredAt
    ): DeliveryResult {
        require(contextKey.isNotBlank() && eventId.isNotBlank()) { "Context key and event id must not be blank" }
        require(payloadFingerprint.isNotBlank()) { "Payload fingerprint must not be blank" }
        expire(deliveredAt)
        val identity = Identity(contextKey, eventId)
        val previous = seen[identity]
        if (previous == null) {
            seen[identity] = Seen(payloadFingerprint, deliveredAt, 1)
            while (seen.size > maxTrackedIdentities) {
                seen.remove(seen.keys.first())
                evicted++
            }
            return DeliveryResult(DeliveryVerdict.APPLY, null)
        }
        previous.deliveries++
        val common = mapOf(
            "firstDeliveredAt" to previous.firstDeliveredAt.toString(),
            "deliveredAt" to deliveredAt.toString(),
            "deliveryCount" to previous.deliveries.toString()
        )
        return if (previous.fingerprint == payloadFingerprint) {
            DeliveryResult(DeliveryVerdict.SKIP_DUPLICATE, Finding(
                FailureClassId.DUPLICATE_REPLAY, Confidence.CONFIRMED, contextKey, detectedAt,
                "Event $eventId was delivered ${previous.deliveries} times with the same payload.",
                eventId = eventId, evidence = common + ("payloadFingerprint" to payloadFingerprint)
            ))
        } else {
            DeliveryResult(DeliveryVerdict.SKIP_CONFLICT, Finding(
                FailureClassId.CONFLICTING_VALUES, Confidence.CONFIRMED, contextKey, detectedAt,
                "Event $eventId was delivered again with a different payload.",
                eventId = eventId, evidence = common + mapOf(
                    "firstPayloadFingerprint" to previous.fingerprint,
                    "payloadFingerprint" to payloadFingerprint
                )
            ))
        }
    }

    @get:Synchronized
    val trackedIdentities: Int
        get() = seen.size

    @get:Synchronized
    val evictedIdentities: Long
        get() = evicted

    private fun expire(now: Instant) {
        val iterator = seen.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            // Insertion order is first-delivery order, so the scan can stop at the first live entry.
            if (Duration.between(entry.firstDeliveredAt, now) <= retention) break
            iterator.remove()
            evicted++
        }
    }
}
