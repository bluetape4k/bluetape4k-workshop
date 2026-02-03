package io.bluetape4k.workshop.coroutines.controller

import io.bluetape4k.concurrent.virtualthread.VT
import io.bluetape4k.coroutines.flow.async
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.coroutines.model.Banner
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import tools.jackson.databind.JsonNode

@RestController
@RequestMapping("/controller/vt")
class VTCoroutineController(
    private val builder: WebClient.Builder,
): CoroutineScope by CoroutineScope(Dispatchers.VT + CoroutineName("vt")) {

    companion object: KLoggingChannel() {
        private const val DEFAULT_DELAY = 100L
    }

    @Value("\${server.port:8080}")
    private val port: String = uninitialized()

    private val client: WebClient by lazy {
        builder.baseUrl("http://localhost:$port").build()
    }

    private val banner = Banner.TEST_BANNER

    private fun currentCoroutineName(): String? = coroutineContext[CoroutineName]?.name


    @GetMapping("", "/", "/index")
    suspend fun index(model: Model): Banner {
        delay(DEFAULT_DELAY)
        return banner
    }

    @GetMapping("/suspend")
    suspend fun suspendingEndpoint(): Banner {
        delay(DEFAULT_DELAY)
        val coroutineName = currentCoroutineName()
        log.debug { "coroutineName=[$coroutineName]" }
        log.info { "Suspending... return $banner" }
        return banner
    }

    @GetMapping("/deferred")
    fun deferredEndpoint(): Deferred<Banner> = async {
        delay(DEFAULT_DELAY)
        val coroutineName = currentCoroutineName()
        log.debug { "coroutineName=[$coroutineName]" }
        log.info { "Deferred ... return $banner" }
        banner
    }

    @GetMapping("/sequential-flow")
    fun sequentialFlow(): Flow<Banner> {
        log.info { "Get banners in sequential mode." }

        return flow {
            repeat(4) {
                val coroutineName = currentCoroutineName()
                log.debug { "coroutineName=[$coroutineName]" }
                emit(retrieveBanner())
            }
        }
    }

    @GetMapping("/concurrent-flow")
    fun concurrentFlow(): Flow<Banner> {
        log.info { "Get banners in concurrent mode." }

        return (0..3).asFlow()
            .async {
                val coroutineName = currentCoroutineName()
                log.debug { "coroutineName=[$coroutineName]" }
                retrieveBanner()
            }
    }

    @GetMapping("/error")
    suspend fun error() {
        log.info { "Error occurred." }
        throw RuntimeException("Error occurred.")
    }

    @PostMapping("/request-as-flow")
    fun requestAsStream(@RequestBody requests: Flow<JsonNode>): Flow<String> {
        return channelFlow {
            requests.collect { node ->
                val coroutineName = currentCoroutineName()
                log.debug { "jsonNode=${node.toPrettyString()}, coroutineName=[$coroutineName]" }
                send(node.toPrettyString())
            }
        }
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
