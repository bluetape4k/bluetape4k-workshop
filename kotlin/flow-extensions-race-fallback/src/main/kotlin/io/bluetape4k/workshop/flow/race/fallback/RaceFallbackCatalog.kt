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
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * multi-source catalog read 에서 bluetape4k Flow source-composition operator 사용법을 보여줍니다.
 *
 * 이 메서드들은 Flow extension API 를 얇게 감싼 wrapper 로 남겨 두어 workshop 이 fastest-wins race, ordered fallback, eager fallback, merge, error-as-value 처리에서 어떤 operator 를 고를지에 집중하게 합니다.
 */
class RaceFallbackCatalog {

    /**
     * deterministic test 를 위한 선택적 lifecycle callback 을 가진 cold source Flow 를 만듭니다.
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
     * 값을 먼저 방출한 source 를 선택하고 경쟁에서 진 source 를 취소합니다.
     */
    fun fastestHealthy(sources: Iterable<Flow<SourceResult>>): Flow<SourceResult> =
        sources.toList()
            .also { it.requireNotEmpty("sources") }
            .race()

    /**
     * fallback source 를 전달된 순서 그대로 엄격하게 collect 합니다.
     */
    fun orderedFallback(sources: Iterable<Flow<SourceResult>>): Flow<SourceResult> =
        sources.toList()
            .also { it.requireNotEmpty("sources") }
            .concat()

    /**
     * source emission order 를 보존하면서 모든 fallback source 를 즉시 시작합니다.
     */
    fun eagerFallback(vararg sources: Flow<SourceResult>): Flow<SourceResult> {
        sources.requireNotEmpty("sources")
        return concatArrayEager(*sources)
    }

    /**
     * source order 를 보존하면서 source identifier 를 eager fallback probe 로 매핑합니다.
     */
    fun eagerFallbackBySource(
        sources: Flow<CatalogSource>,
        sourceFactory: suspend (CatalogSource) -> Flow<SourceResult>,
    ): Flow<SourceResult> = sources.concatMapEager(sourceFactory)

    /**
     * source order 를 보존하면서 동시 inner 수와 inner 별 출력 queue 용량을 제한합니다.
     *
     * [maxConcurrency] 는 동시에 수집할 inner Flow 의 최대 개수이고,
     * [bufferCapacity] 는 inner 별 출력 queue 의 최대 용량입니다. `0` 은 rendezvous
     * queue 이며, 앞선 source 가 느려도 뒤 source 는 설정한 queue 까지만 누적됩니다.
     */
    fun boundedEagerFallbackBySource(
        sources: Flow<CatalogSource>,
        maxConcurrency: Int,
        bufferCapacity: Int = maxConcurrency,
        sourceFactory: suspend (CatalogSource) -> Flow<SourceResult>,
    ): Flow<SourceResult> {
        maxConcurrency.requirePositiveNumber("maxConcurrency")
        bufferCapacity.requireZeroOrPositiveNumber("bufferCapacity")
        return sources.concatMapEager(
            maxConcurrency = maxConcurrency,
            bufferCapacity = bufferCapacity,
            transform = sourceFactory,
        )
    }

    /**
     * 모든 source contribution 을 도착 순서대로 merge 합니다.
     */
    fun mergeContributions(vararg sources: Flow<SourceResult>): Flow<SourceResult> {
        sources.requireNotEmpty("sources")
        return merge(*sources)
    }

    /**
     * terminal error 와 completion 을 value event 로 변환합니다.
     */
    fun materialized(source: Flow<SourceResult>): Flow<FlowEvent<SourceResult>> = source.materialize()

    /**
     * Converts materialized value events back into a terminal-error Flow.
     */
    fun dematerialized(source: Flow<FlowEvent<SourceResult>>): Flow<SourceResult> = source.dematerialize()
}
