package io.intenttrace.pipeline.model

import java.time.Instant
import java.util.Collections

/**
 * Immutable contextual state available to the decision pipeline.
 *
 * A logical context may have multiple versions over time. The version and
 * commit timestamp allow the pipeline and future benchmark scenarios to
 * distinguish the state used by a decision from newer eligible state.
 */
class Context(
    val id: String,
    val version: Long,
    val committedAt: Instant,
    attributes: Map<String, String>
) {
    val attributes: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(attributes))

    init {
        require(id.isNotBlank()) {
            "Context id must not be blank"
        }

        require(version > 0) {
            "Context version must be greater than zero"
        }
    }

    // Keep value operations explicit so construction and copying both snapshot inputs.
    fun copy(
        id: String = this.id,
        version: Long = this.version,
        committedAt: Instant = this.committedAt,
        attributes: Map<String, String> = this.attributes
    ): Context = Context(id, version, committedAt, attributes)

    operator fun component1(): String = id
    operator fun component2(): Long = version
    operator fun component3(): Instant = committedAt
    operator fun component4(): Map<String, String> = attributes

    override fun equals(other: Any?): Boolean =
        this === other || (other is Context &&
            id == other.id && version == other.version &&
            committedAt == other.committedAt && attributes == other.attributes)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + committedAt.hashCode()
        return 31 * result + attributes.hashCode()
    }

    override fun toString(): String =
        "Context(id=$id, version=$version, committedAt=$committedAt, attributes=$attributes)"
}
