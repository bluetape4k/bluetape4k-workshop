package io.bluetape4k.workshop.flow.race.fallback

import io.bluetape4k.coroutines.flow.extensions.FlowEvent
import io.bluetape4k.coroutines.flow.extensions.concat
import io.bluetape4k.coroutines.flow.extensions.concatArrayEager
import io.bluetape4k.coroutines.flow.extensions.concatMapEager
import io.bluetape4k.coroutines.flow.extensions.dematerialize
import io.bluetape4k.coroutines.flow.extensions.materialize
import io.bluetape4k.coroutines.flow.extensions.merge
import io.bluetape4k.coroutines.flow.extensions.race
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireZeroOrPositiveNumber
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Demonstrates bluetape4k Flow source-composition operators for multi-source catalog reads.
 *
 * The methods are intentionally thin wrappers around the Flow extension APIs so the workshop can
 * focus on operator selection: fastest-wins race, ordered fallback, eager fallback, merge, and
 * error-as-value handling.
 */
class RaceFallbackCatalog {

    /**
     * Builds a cold source Flow with optional lifecycle callbacks for deterministic tests.
     */
    fun source(
        result: SourceResult,
        delayMs: Long = result.latencyMs,
        onStart: suspend () -> Unit = {},
        onCancel: suspend () -> Unit = {},
        failWith: Throwable? = null,
    ): Flow<SourceResult> {
        delayMs.requireZeroOrPositiveNumber("delayMs")

        return flow {
            onStart()
            try {
                delay(delayMs)
                if (failWith != null) {
                    throw failWith
                }
                emit(result)
            } finally {
                if (!currentCoroutineContext().isActive) {
                    onCancel()
                }
            }
        }
    }

    /**
     * Selects the first source that emits a value and cancels losing sources.
     */
    fun fastestHealthy(sources: Iterable<Flow<SourceResult>>): Flow<SourceResult> =
        sources.toList()
            .also { it.requireNotEmpty("sources") }
            .race()

    /**
     * Collects fallback sources strictly in the provided order.
     */
    fun orderedFallback(sources: Iterable<Flow<SourceResult>>): Flow<SourceResult> =
        sources.toList()
            .also { it.requireNotEmpty("sources") }
            .concat()

    /**
     * Starts all fallback sources immediately while preserving source emission order.
     */
    fun eagerFallback(vararg sources: Flow<SourceResult>): Flow<SourceResult> {
        sources.requireNotEmpty("sources")
        return concatArrayEager(*sources)
    }

    /**
     * Maps source identifiers to eager fallback probes while preserving source order.
     */
    fun eagerFallbackBySource(
        sources: Flow<CatalogSource>,
        sourceFactory: suspend (CatalogSource) -> Flow<SourceResult>,
    ): Flow<SourceResult> = sources.concatMapEager(sourceFactory)

    /**
     * Merges all source contributions by arrival order.
     */
    fun mergeContributions(vararg sources: Flow<SourceResult>): Flow<SourceResult> {
        sources.requireNotEmpty("sources")
        return merge(*sources)
    }

    /**
     * Converts terminal errors and completion into value events.
     */
    fun materialized(source: Flow<SourceResult>): Flow<FlowEvent<SourceResult>> = source.materialize()

    /**
     * Converts materialized value events back into a terminal-error Flow.
     */
    fun dematerialized(source: Flow<FlowEvent<SourceResult>>): Flow<SourceResult> = source.dematerialize()
}
