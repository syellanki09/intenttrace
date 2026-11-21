package io.intenttrace.detector

import java.time.Instant
import java.util.Collections

/**
 * Canonical IntentTrace failure-class identifiers.
 *
 * The detector keeps its own copy so that it has no dependency on the
 * benchmark harness. A test asserts that every identifier matches the
 * taxonomy used by the evaluation module.
 */
enum class FailureClassId(val id: String) {
    STALE_CONTEXT("stale-context"),
    MISSING_CONTEXT("missing-context"),
    DELAYED_EVENTS("delayed-events"),
    DUPLICATE_REPLAY("duplicate-replay"),
    CONFLICTING_VALUES("conflicting-values"),
    CONTRACT_INCOMPATIBILITY("contract-incompatibility")
}

/** How strongly the evidence supports a finding. */
enum class Confidence {
    /**
     * Evidence supplied by the host system establishes the condition, assuming
     * that evidence is itself correct: for example an authoritative version that
     * is newer than the one used, or an event identity delivered twice.
     */
    CONFIRMED,

    /** A heuristic threshold, such as context age, was exceeded. */
    SUSPECTED
}

/**
 * One information-integrity condition observed by the detector at runtime.
 *
 * A finding is an observation from the system under test. It is deliberately
 * not the same thing as benchmark ground truth: a host can receive false
 * positives (for example from an over-strict age threshold) and false
 * negatives (for example when its version oracle is itself stale).
 */
class Finding(
    val failureClass: FailureClassId,
    val confidence: Confidence,
    val contextKey: String,
    val detectedAt: Instant,
    val explanation: String,
    val decisionId: String? = null,
    val eventId: String? = null,
    evidence: Map<String, String> = emptyMap()
) {
    /** Supporting values, sorted by key and protected from later caller mutation. */
    val evidence: Map<String, String> = Collections.unmodifiableMap(evidence.toSortedMap())

    init {
        require(contextKey.isNotBlank()) { "Finding context key must not be blank" }
        require(explanation.isNotBlank()) { "Finding explanation must not be blank" }
        require(decisionId == null || decisionId.isNotBlank()) { "Decision id must not be blank when present" }
        require(eventId == null || eventId.isNotBlank()) { "Event id must not be blank when present" }
        require(evidence.keys.none { it.isBlank() }) { "Evidence keys must not be blank" }
    }

    val failureClassId: String
        get() = failureClass.id

    override fun equals(other: Any?): Boolean =
        this === other || (other is Finding && failureClass == other.failureClass &&
            confidence == other.confidence && contextKey == other.contextKey &&
            detectedAt == other.detectedAt && explanation == other.explanation &&
            decisionId == other.decisionId && eventId == other.eventId && evidence == other.evidence)

    override fun hashCode(): Int =
        listOf(failureClass, confidence, contextKey, detectedAt, explanation, decisionId, eventId, evidence)
            .hashCode()

    override fun toString(): String =
        "Finding(failureClass=${failureClass.id}, confidence=$confidence, contextKey=$contextKey, " +
            "decisionId=$decisionId, eventId=$eventId, detectedAt=$detectedAt, explanation=$explanation, " +
            "evidence=$evidence)"
}

/** Receives findings. Implementations must be safe to call from several threads. */
fun interface FindingSink {
    fun accept(finding: Finding)
}

/** Keeps findings in memory, mainly for tests and small demonstrations. */
class CollectingFindingSink : FindingSink {
    private val collected = mutableListOf<Finding>()

    @Synchronized
    override fun accept(finding: Finding) {
        collected += finding
    }

    @Synchronized
    fun findings(): List<Finding> = collected.toList()

    @Synchronized
    fun clear() = collected.clear()
}
