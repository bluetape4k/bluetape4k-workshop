package io.bluetape4k.workshop.flow.search.pipeline

/**
 * Suspend boundary for an autocomplete search backend.
 *
 * Implementations must preserve coroutine cancellation. Blocking backends
 * should be wrapped with `withContext(Dispatchers.IO)` outside this example.
 */
fun interface SearchAdapter {

    /**
     * Executes one search request.
     */
    suspend fun search(request: SearchRequest): SearchResult
}
