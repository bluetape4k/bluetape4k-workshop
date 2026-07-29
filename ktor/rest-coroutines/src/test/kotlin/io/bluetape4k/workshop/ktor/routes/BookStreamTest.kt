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
     * book 이 POST 된 뒤 SSE stream 이 event 를 방출하는지 검증합니다.
     *
     * ## Implementation notes
     * - `testApplication` receiver 는 `CoroutineScope` 가 아니라 `ApplicationTestBuilder` 입니다.
     * - 여기서는 `backgroundScope` 에 접근할 수 없으므로 `CoroutineScope(coroutineContext + SupervisorJob())` 를 사용합니다.
     * - 먼저 subscribe 한 뒤 hot SharedFlow 에 subscription 이 등록될 수 있도록 delay 합니다.
     */
    @Test
    fun `SSE stream emits event after POST`() = testApplication {
        val preseeded = InMemoryBookRepository()
        application { module(repository = preseeded) }

        val sseClient = createClient { install(SSE) }
        val events = Channel<String>(Channel.BUFFERED)

        // testApplication block receiver 는 ApplicationTestBuilder 입니다. CoroutineScope/TestScope 가 아닙니다.
        // 여기서는 bare launch{} 나 backgroundScope.launch{} 에 접근할 수 없습니다.
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

        // subscribe 전에 book 을 POST 합니다. hot SharedFlow 이므로 늦게 구독한 subscriber 는 이 event 를 놓칩니다.
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

        // 늦게 구독한 subscriber 는 이미 방출된 event 를 받지 않아야 합니다.
        val received = withTimeoutOrNull(500) { events.receive() }
        received shouldBeEqualTo null

        subscription.cancel()
        subscriptionScope.cancel()
        events.close()
    }
}
