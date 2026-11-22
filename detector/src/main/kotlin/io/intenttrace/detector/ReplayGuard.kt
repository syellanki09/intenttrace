package io.intenttrace.detector

import java.time.Duration
import java.time.Instant
import java.util.PriorityQueue

/** What the host should do with one delivery. */
enum class DeliveryVerdict {
    APPLY,

    /** The same identity and payload were already delivered within retention. */
    SKIP_DUPLICATE,

    /** The same identity arrived with a different payload; neither copy is trustworthy by itself. */
    SKIP_CONFLICT
}

/**
 * The outcome of one delivery. When the verdict is [DeliveryVerdict.APPLY],
 * [attempt] identifies this delivery so that a failure to apply it can be
 * reported with [ReplayGuard.onApplicationFailed].
 */
data class DeliveryResult(
    val verdict: DeliveryVerdict,
    val finding: Finding?,
    val attempt: DeliveryAttempt? = null
) {
    val shouldApply: Boolean
        get() = verdict == DeliveryVerdict.APPLY
}

/** One recorded delivery attempt. Opaque to hosts; it only identifies the attempt. */
class DeliveryAttempt internal constructor(
    val contextKey: String,
    val eventId: String,
    internal val owner: Any,
    internal val sequence: Long
) {
    override fun toString(): String = "DeliveryAttempt(contextKey=$contextKey, eventId=$eventId, sequence=$sequence)"
}

/**
 * Detects repeated and conflicting deliveries of the same logical event.
 *
 * The guard remembers each `(contextKey, eventId)` for [retention] after its
 * first delivery, up to [maxTrackedIdentities]. A host may follow the verdict
 * (idempotent processing) or only record the finding (observation mode).
 *
 * An identity is recorded when the guard returns [DeliveryVerdict.APPLY]. If
 * the host then fails to apply that delivery, it passes the result to
 * [onApplicationFailed] so the retry is applied instead of being skipped as a
 * duplicate. The result names the specific attempt, so a late failure report
 * from an attempt that already expired or was evicted cannot release a newer
 * delivery of the same identity.
 *
 * Retention is measured from each identity's own delivery time, so deliveries
 * whose timestamps are out of order (for example from concurrent callers)
 * still expire on time.
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
    private class Seen(val fingerprint: String, val firstDeliveredAt: Instant, val attemptSequence: Long, var deliveries: Int)
    private class Expiry(val identity: Identity, val entry: Seen)

    /** Insertion order, used for capacity eviction. */
    private val seen = LinkedHashMap<Identity, Seen>()

    /** Delivery-time order, used for retention. Stale entries are skipped when polled. */
    private val expiries = PriorityQueue<Expiry>(compareBy { it.entry.firstDeliveredAt })
    private var evicted = 0L
    private var lastAttemptSequence = 0L

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
            val entry = Seen(payloadFingerprint, deliveredAt, ++lastAttemptSequence, 1)
            seen[identity] = entry
            expiries.add(Expiry(identity, entry))
            while (seen.size > maxTrackedIdentities) {
                seen.remove(seen.keys.first())
                evicted++
            }
            compactExpiries()
            return DeliveryResult(DeliveryVerdict.APPLY, null,
                DeliveryAttempt(contextKey, eventId, this, entry.attemptSequence))
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

    /**
     * Call with the result of [onDelivery] when the host could not apply that
     * delivery. The identity is forgotten, so the next delivery of it is
     * applied. Returns false, and changes nothing, when the result carries no
     * attempt (it was not an [DeliveryVerdict.APPLY]), came from another guard,
     * was already released, or no longer matches the tracked delivery because
     * that attempt expired or was evicted and a newer one was recorded.
     *
     * A duplicate that arrived while the failed application was in flight was
     * already reported and skipped; the retry after this call is what recovers
     * the event.
     */
    @Synchronized
    fun onApplicationFailed(delivery: DeliveryResult): Boolean {
        val attempt = delivery.attempt ?: return false
        if (attempt.owner !== this) return false
        val identity = Identity(attempt.contextKey, attempt.eventId)
        val entry = seen[identity] ?: return false
        if (entry.attemptSequence != attempt.sequence) return false
        seen.remove(identity)
        compactExpiries()
        return true
    }

    @get:Synchronized
    val trackedIdentities: Int
        get() = seen.size

    @get:Synchronized
    val evictedIdentities: Long
        get() = evicted

    private fun expire(now: Instant) {
        while (true) {
            val next = expiries.peek() ?: return
            if (Duration.between(next.entry.firstDeliveredAt, now) <= retention) return
            expiries.poll()
            if (seen[next.identity] === next.entry) {
                seen.remove(next.identity)
                evicted++
            }
        }
    }

    /** Drops queue entries for identities that were already evicted or released. */
    private fun compactExpiries() {
        if (expiries.size <= 2 * seen.size + 64) return
        expiries.clear()
        seen.forEach { (identity, entry) -> expiries.add(Expiry(identity, entry)) }
    }
}
