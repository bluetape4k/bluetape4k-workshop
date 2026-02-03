package io.bluetape4k.workshop.coroutines.handler

import io.bluetape4k.coroutines.flow.async
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.coroutines.model.Banner
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyAndAwait
import org.springframework.web.reactive.function.server.bodyValueAndAwait

@Component
class CoroutineHandler(
    private val builder: WebClient.Builder,
): CoroutineScope by CoroutineScope(Dispatchers.IO + CoroutineName("handler")) {

    companion object: KLoggingChannel() {
        private const val DEFAULT_DELAY = 100L
    }

    @Value("\${server.port:8080}")
    private val port: String = uninitialized()

    // 응답용 객체
    private val banner = Banner.TEST_BANNER

    // API Server에서 다른 API 서버를 호출하는 것을 흉내내기 위해서 사용합니다.
    private val client by lazy { builder.baseUrl("http://localhost:$port").build() }

    private suspend fun currentCoroutineName(): String? = coroutineContext[CoroutineName]?.name

    suspend fun index(request: ServerRequest): ServerResponse {
        delay(DEFAULT_DELAY)

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(banner)
        //.renderAndAwait("index", mapOf("banner" to banner))
    }

    suspend fun suspending(request: ServerRequest): ServerResponse {
        delay(DEFAULT_DELAY)
        log.info { "Suspending... return $banner" }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(banner)
    }

    suspend fun deferred(request: ServerRequest): ServerResponse = coroutineScope {
        val body = async {
            delay(DEFAULT_DELAY)
            banner
        }
        log.info { "Deferred... return $banner" }

        ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(body.await())
    }

    suspend fun sequentialFlow(request: ServerRequest): ServerResponse {
        log.info { "Get banners in sequential mode." }

        val flow = flow {
            repeat(4) {
                emit(retrieveBanner())
            }
        }
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyAndAwait(flow)
    }

    suspend fun concurrentFlow(request: ServerRequest): ServerResponse {
        log.info { "Get banners in concurrent mode." }

        val flow = (0..3).asFlow()
            .async {
                retrieveBanner()
            }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyAndAwait(flow)
    }

    suspend fun error(request: ServerRequest): ServerResponse {
        log.info { "Error occurred." }
        throw RuntimeException("Error occurred.")
    }

    private suspend fun retrieveBanner(): Banner {
        log.debug { "Retrieve banner from /suspend" }

        return client.get()
            .uri("/suspend")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .awaitBody<Banner>()
    }
}
