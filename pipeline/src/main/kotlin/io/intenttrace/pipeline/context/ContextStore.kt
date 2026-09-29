package io.intenttrace.pipeline.context

import io.intenttrace.pipeline.model.Context
import java.time.Instant

/**
 * Stores versioned context and exposes the latest state that was eligible
 * at a specific decision boundary.
 */
interface ContextStore {
    fun save(context: Context)

    fun find(
        contextId: String,
        version: Long
    ): Context?

    fun latestEligible(
        contextId: String,
        decidedAt: Instant
    ): Context?
}

/**
 * Deterministic in-memory store used by the reference pipeline and benchmarks.
 *
 * This implementation is deliberately single-threaded. Benchmark scenarios
 * control execution order explicitly rather than introducing synchronization
 * behavior into the reference store.
 *
 * Context history is independent from resolution policy so a fault injector
 * can force an older read without changing what was actually available.
 */
class InMemoryContextStore : ContextStore {

    private val contextsById =
        mutableMapOf<String, MutableList<Context>>()

    override fun save(context: Context) {
        val versions = contextsById.getOrPut(context.id) {
            mutableListOf()
        }

        require(
            versions.none {
                it.version == context.version
            }
        ) {
            "Context ${context.id} version " +
                    "${context.version} already exists"
        }

        versions.forEach { existing ->
            when {
                context.version > existing.version -> {
                    require(
                        !context.committedAt.isBefore(
                            existing.committedAt
                        )
                    ) {
                        "Higher context version cannot be committed " +
                                "before a lower version"
                    }
                }

                context.version < existing.version -> {
                    require(
                        !context.committedAt.isAfter(
                            existing.committedAt
                        )
                    ) {
                        "Lower context version cannot be committed " +
                                "after a higher version"
                    }
                }
            }
        }

        versions += context
    }

    override fun find(
        contextId: String,
        version: Long
    ): Context? =
        contextsById[contextId]
            ?.firstOrNull {
                it.version == version
            }

    override fun latestEligible(
        contextId: String,
        decidedAt: Instant
    ): Context? =
        contextsById[contextId]
            ?.asSequence()
            ?.filter {
                !it.committedAt.isAfter(decidedAt)
            }
            ?.maxWithOrNull(
                compareBy<Context> {
                    it.committedAt
                }.thenBy {
                    it.version
                }
            )
}
