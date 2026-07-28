package io.bluetape4k.workshop.flow.search.pipeline

import io.bluetape4k.coroutines.flow.extensions.bufferingDebounce
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.coroutines.flow.extensions.takeUntil
import io.bluetape4k.coroutines.flow.extensions.withLatestFrom
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireLt
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * realtime search/autocomplete Flow pipeline 입니다.
 *
 * `settings` 는 `MutableStateFlow(initialSettings)` 처럼 seed 값이 있는 hot/state-like Flow 여야 합니다. 첫 settings 값 이전에 방출된 query 는 `withLatestFrom` 에 의해 의도적으로 버려집니다.
 */
class SearchPipeline(
    private val adapter: SearchAdapter,
) {

    /**
     * raw query input, latest settings, session stop signal 로 search result stream 을 만듭니다.
     *
     * 새 query 는 `flatMapLatest` 를 통해 오래된 in-flight search 를 취소합니다. session close 는 하나의 shared signal 로 정규화되어 모든 adapter call 과 경쟁하므로, downstream output 만 억제하는 대신 중단된 search 작업 자체를 취소합니다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun search(
        queries: Flow<String>,
        settings: Flow<SearchSettings>,
        sessionClosed: Flow<Unit>,
        debounce: Duration,
    ): Flow<SearchResult> {
        debounce.requirePositiveFinite("debounce")

        return channelFlow {
            val sharedSessionClosed = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
            val stopCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                sessionClosed.take(1).collect {
                    sharedSessionClosed.emit(Unit)
                }
            }

            try {
                queries
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .bufferingDebounce(debounce)
                    .mapNotNull { burst -> burst.lastOrNull()?.let { SearchQuery(it) } }
                    .withLatestFrom(settings) { query, latestSettings -> SearchRequest(query, latestSettings) }
                    .flatMapLatest { request -> searchUntilSessionClosed(request, sharedSessionClosed) }
                    .takeUntil(sharedSessionClosed)
                    .collect { send(it) }
            } finally {
                stopCollector.cancelAndJoin()
            }
        }.log("search-pipeline")
    }

    private fun searchUntilSessionClosed(
        request: SearchRequest,
        sharedSessionClosed: Flow<Unit>,
    ): Flow<SearchResult> = flow {
        coroutineScope {
            val search = async(start = CoroutineStart.UNDISPATCHED) {
                adapter.search(request)
            }
            val stop = async(start = CoroutineStart.UNDISPATCHED) {
                sharedSessionClosed.first()
            }

            try {
                val result = select<SearchResult?> {
                    search.onAwait { it }
                    stop.onAwait { null }
                }

                if (result == null) {
                    search.cancelAndJoin()
                } else {
                    stop.cancelAndJoin()
                    emit(result)
                }
            } finally {
                if (search.isActive) {
                    search.cancelAndJoin()
                }
                if (stop.isActive) {
                    stop.cancelAndJoin()
                }
            }
        }
    }
}

private fun Duration.requirePositiveFinite(parameterName: String): Duration = apply {
    val nanoseconds = toDouble(DurationUnit.NANOSECONDS)
    nanoseconds.requireGt(0.0, parameterName)
    nanoseconds.requireLt(Double.POSITIVE_INFINITY, parameterName)
}
