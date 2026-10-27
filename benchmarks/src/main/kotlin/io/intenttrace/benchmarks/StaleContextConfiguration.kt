@file:kotlinx.serialization.UseSerializers(io.intenttrace.provenance.InstantSerializer::class)

package io.intenttrace.benchmarks

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Versioned inputs for a paired stale-context experiment; defaults preserve scenario v1. */
@Serializable
data class StaleContextConfiguration(
    val configurationVersion: Int = 1,
    val contextId: String = "user-001",
    val olderVersion: Long = 1,
    val latestVersion: Long = 2,
    val olderCommittedAt: Instant = Instant.parse("2026-01-01T00:00:01Z"),
    val latestCommittedAt: Instant = Instant.parse("2026-01-01T00:00:02Z"),
    val decidedAt: Instant = Instant.parse("2026-01-01T00:00:03Z"),
    val olderCategory: String = "travel",
    val latestCategory: String = "technology"
) {
    init {
        require(configurationVersion == 1) { "Unsupported configuration version: $configurationVersion" }
        require(contextId.isNotBlank()) { "Context ID must not be blank" }
        require(olderVersion > 0 && latestVersion > olderVersion) { "Versions must be positive and strictly increasing" }
        require(!olderCommittedAt.isAfter(latestCommittedAt) && !latestCommittedAt.isAfter(decidedAt)) {
            "Commit times must be ordered and both contexts eligible at the decision boundary"
        }
        require(olderCategory.isNotBlank() && latestCategory.isNotBlank() && olderCategory != latestCategory) {
            "Categories must be nonblank and distinct so injection changes the outcome"
        }
    }

    fun metadata(): Map<String, String> = sortedMapOf(
        "configurationVersion" to configurationVersion.toString(),
        "contextId" to contextId,
        "olderVersion" to olderVersion.toString(),
        "latestVersion" to latestVersion.toString(),
        "pinnedVersion" to olderVersion.toString(),
        "olderCommittedAt" to olderCommittedAt.toString(),
        "latestCommittedAt" to latestCommittedAt.toString(),
        "decidedAt" to decidedAt.toString(),
        "olderCategory" to olderCategory,
        "latestCategory" to latestCategory
    )

    companion object {
        /** Unknown keys and invalid values fail instead of silently falling back. */
        fun decode(json: String): StaleContextConfiguration = Json.decodeFromString(json)
    }
}
