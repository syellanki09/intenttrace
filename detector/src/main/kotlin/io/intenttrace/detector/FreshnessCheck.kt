package io.intenttrace.detector

import java.time.Duration
import java.time.Instant

/** A committed context version and the time it became visible to readers. */
data class VersionStamp(val version: Long, val committedAt: Instant) {
    init {
        require(version > 0) { "Context version must be greater than zero" }
    }
}

/**
 * Answers which context version was the latest eligible one at a decision
 * boundary, from a source the host trusts more than the decision path's read:
 * a primary database, a change log, or a version counter.
 *
 * Detection is only as good as this oracle. An oracle that lags the truth hides
 * staleness instead of revealing it.
 */
fun interface LatestVersionOracle {
    fun latestEligible(contextKey: String, decidedAt: Instant): VersionStamp?
}

/** What one decision actually read. [used] is null when the decision proceeded without context. */
data class ContextRead(
    val decisionId: String,
    val contextKey: String,
    val decidedAt: Instant,
    val used: VersionStamp?
) {
    init {
        require(decisionId.isNotBlank()) { "Decision id must not be blank" }
        require(contextKey.isNotBlank()) { "Context key must not be blank" }
    }
}

/**
 * Checks, at decision time, whether a decision used the latest eligible context.
 *
 * With an oracle, a version lag greater than [maxVersionLag] is a confirmed
 * stale-context finding, and a missing read while eligible context exists is a
 * confirmed missing-context finding. Without an oracle verdict, a read older
 * than [maxAge] is a suspected stale-context finding.
 *
 * Versions are assumed to be positive and to increase with every commit.
 */
class FreshnessCheck(
    private val oracle: LatestVersionOracle? = null,
    private val maxVersionLag: Long = 0,
    private val maxAge: Duration? = null
) {
    init {
        require(oracle != null || maxAge != null) { "Freshness checks need an oracle, a maximum age, or both" }
        require(maxVersionLag >= 0) { "Maximum version lag must not be negative" }
        require(maxAge == null || (!maxAge.isNegative && !maxAge.isZero)) { "Maximum age must be positive" }
    }

    fun check(read: ContextRead, detectedAt: Instant): List<Finding> {
        val used = read.used
        // Clock skew between writers and the decision host is common, so impossible
        // timestamps are reported as suspected disagreements instead of failing the host.
        if (used != null && used.committedAt.isAfter(read.decidedAt)) {
            return listOf(skew(read, "read", used, detectedAt))
        }
        val latest = oracle?.latestEligible(read.contextKey, read.decidedAt)
        if (latest != null && latest.committedAt.isAfter(read.decidedAt)) {
            return listOf(skew(read, "oracle", latest, detectedAt))
        }
        if (used == null) {
            return listOfNotNull(latest?.let { missing(read, it, detectedAt) })
        }
        if (latest != null) {
            return when {
                used.version > latest.version -> listOf(disagreement(read, used, latest, detectedAt))
                latest.version - used.version > maxVersionLag -> listOf(stale(read, used, latest, detectedAt))
                else -> emptyList()
            }
        }
        val age = Duration.between(used.committedAt, read.decidedAt)
        return if (maxAge != null && age > maxAge) listOf(aged(read, used, age, detectedAt)) else emptyList()
    }

    private fun missing(read: ContextRead, latest: VersionStamp, at: Instant) = Finding(
        FailureClassId.MISSING_CONTEXT, Confidence.CONFIRMED, read.contextKey, at,
        "Decision used no context although version ${latest.version} was eligible.",
        decisionId = read.decisionId,
        evidence = mapOf(
            "decidedAt" to read.decidedAt.toString(),
            "latestEligibleVersion" to latest.version.toString(),
            "latestEligibleCommittedAt" to latest.committedAt.toString()
        )
    )

    private fun stale(read: ContextRead, used: VersionStamp, latest: VersionStamp, at: Instant) = Finding(
        FailureClassId.STALE_CONTEXT, Confidence.CONFIRMED, read.contextKey, at,
        "Decision used context version ${used.version} instead of latest eligible version ${latest.version}.",
        decisionId = read.decisionId,
        evidence = mapOf(
            "decidedAt" to read.decidedAt.toString(),
            "usedVersion" to used.version.toString(),
            "usedCommittedAt" to used.committedAt.toString(),
            "latestEligibleVersion" to latest.version.toString(),
            "latestEligibleCommittedAt" to latest.committedAt.toString(),
            "versionLag" to (latest.version - used.version).toString(),
            "maxVersionLag" to maxVersionLag.toString(),
            "newerVersionVisibleFor" to Duration.between(latest.committedAt, read.decidedAt).toString()
        )
    )

    private fun disagreement(read: ContextRead, used: VersionStamp, latest: VersionStamp, at: Instant) = Finding(
        FailureClassId.CONFLICTING_VALUES, Confidence.SUSPECTED, read.contextKey, at,
        "Decision used version ${used.version}, newer than the oracle's latest eligible version " +
            "${latest.version}; the read path and the oracle disagree.",
        decisionId = read.decisionId,
        evidence = mapOf(
            "decidedAt" to read.decidedAt.toString(),
            "usedVersion" to used.version.toString(),
            "oracleLatestEligibleVersion" to latest.version.toString()
        )
    )

    private fun skew(read: ContextRead, source: String, stamp: VersionStamp, at: Instant) = Finding(
        FailureClassId.CONFLICTING_VALUES, Confidence.SUSPECTED, read.contextKey, at,
        "The $source reported version ${stamp.version} committed at ${stamp.committedAt}, after the decision " +
            "at ${read.decidedAt}; clocks or sources disagree.",
        decisionId = read.decisionId,
        evidence = mapOf(
            "decidedAt" to read.decidedAt.toString(),
            "source" to source,
            "version" to stamp.version.toString(),
            "committedAt" to stamp.committedAt.toString()
        )
    )

    private fun aged(read: ContextRead, used: VersionStamp, age: Duration, at: Instant) = Finding(
        FailureClassId.STALE_CONTEXT, Confidence.SUSPECTED, read.contextKey, at,
        "Decision used context version ${used.version} committed $age before the decision, " +
            "older than the configured maximum age $maxAge.",
        decisionId = read.decisionId,
        evidence = mapOf(
            "decidedAt" to read.decidedAt.toString(),
            "usedVersion" to used.version.toString(),
            "usedCommittedAt" to used.committedAt.toString(),
            "age" to age.toString(),
            "maxAge" to maxAge.toString()
        )
    )
}
