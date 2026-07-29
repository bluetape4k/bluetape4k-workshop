package io.bluetape4k.workshop.flow.search.pipeline

/**
 * autocomplete search backend 를 호출하는 suspend boundary 입니다.
 *
 * 구현체는 coroutine cancellation 을 보존해야 합니다. blocking backend 는 이 예제 밖에서 `withContext(Dispatchers.IO)` 로 감싸야 합니다.
 */
fun interface SearchAdapter {

    /**
     * 하나의 search request 를 실행합니다.
     */
    suspend fun search(request: SearchRequest): SearchResult
}
