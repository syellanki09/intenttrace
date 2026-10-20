package io.intenttrace.evaluation

/** Stable taxonomy identifiers for grouping results and future serialization. */
enum class FailureClass(val id: String) {
    STALE_CONTEXT("stale-context"),
    MISSING_CONTEXT("missing-context"),
    DELAYED_EVENTS("delayed-events"),
    DUPLICATE_REPLAY("duplicate-replay"),
    CONFLICTING_VALUES("conflicting-values"),
    CONTRACT_INCOMPATIBILITY("contract-incompatibility")
}
