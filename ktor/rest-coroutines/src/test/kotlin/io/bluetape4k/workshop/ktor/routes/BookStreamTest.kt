package io.bluetape4k.workshop.ktor.routes

import io.bluetape4k.workshop.ktor.AbstractKtorTest
import io.bluetape4k.workshop.ktor.module
import io.bluetape4k.workshop.ktor.repository.InMemoryBookRepository
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test

class BookStreamTest : AbstractKtorTest() {

    /**
     * Verifies SSE stream emits an event after a book is POSTed.
     *
     * ## Implementation notes
     * - `testApplication` receiver is `ApplicationTestBuilder`, NOT a `CoroutineScope`.
     * - `backgroundScope` is not accessible here; use `CoroutineScope(coroutineContext + SupervisorJob())`.
     * - Subscribe first, then delay to let the subscription register on the hot SharedFlow.
     */
    @Test
    fun `SSE stream emits event after POST`() = testApplication {
        val preseeded = InMemoryBookRepository()
        application { module(repository = preseeded) }

        val sseClient = createClient { install(SSE) }
        val events = Channel<String>(Channel.BUFFERED)

        // testApplication block receiver = ApplicationTestBuilder (not CoroutineScope/TestScope).
        // Neither bare launch{} nor backgroundScope.launch{} is accessible here.
        val subscriptionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val subscription = subscriptionScope.launch {
            sseClient.sse("/books/stream") {
                incoming.collect { event ->
                    event.data?.let { events.send(it) }
                }
            }
        }

        delay(100) // allow subscription to register on hot SharedFlow

        val response = client.post("/books") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"stream-1","title":"Streaming Book","author":"Author","year":2024}""")
        }
        response.status shouldBeEqualTo HttpStatusCode.Created

        val received = withTimeoutOrNull(5_000) { events.receive() }
        received.shouldNotBeNull()
        received shouldContain "stream-1"

        subscription.cancel()
        subscriptionScope.cancel()
        events.close()
    }

    @Test
    fun `SSE stream does not deliver events to late subscriber`() = testApplication {
        val repository = InMemoryBookRepository()
        application { module(repository = repository) }

        // POST a book BEFORE subscribing — hot SharedFlow means late subscriber misses it
        val postResponse = client.post("/books") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"early-1","title":"Early Book","author":"Author","year":2023}""")
        }
        postResponse.status shouldBeEqualTo HttpStatusCode.Created

        delay(50) // ensure POST processed

        val sseClient = createClient { install(SSE) }
        val events = Channel<String>(Channel.BUFFERED)

        val subscriptionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val subscription = subscriptionScope.launch {
            sseClient.sse("/books/stream") {
                incoming.collect { event ->
                    event.data?.let { events.send(it) }
                }
            }
        }

        delay(100) // allow subscription to connect

        // Late subscriber should receive nothing for the already-emitted event
        val received = withTimeoutOrNull(500) { events.receive() }
        received shouldBeEqualTo null

        subscription.cancel()
        subscriptionScope.cancel()
        events.close()
    }
}
