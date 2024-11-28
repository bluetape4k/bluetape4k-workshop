package io.bluetape4k.workshop.webflux.virtualthread.controller

import com.fasterxml.jackson.databind.JsonNode
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.webflux.virtualthread.model.Banner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import net.datafaker.Faker
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient

@RestController
abstract class AbstractDispatcherController(
    protected val webClientBuilder: WebClient.Builder,
) {

    companion object: KLogging() {
        protected val faker = Faker()

        protected const val DEFAULT_DELAY = 1000L
        protected const val FLOW_SIZE = 4
    }

    @Value("\${server.port:8080}")
    private val port: String = uninitialized()

    protected abstract val dispatcher: CoroutineDispatcher

    protected abstract val path: String

    protected val client: WebClient by lazy { getClient("http://localhost:$port/$path") }

    protected fun getClient(baseUrl: String): WebClient {
        return webClientBuilder.baseUrl(baseUrl).build()
    }

    protected fun randomBanner(): Banner =
        Banner(faker.book().title(), faker.lorem().sentence())

    protected val currentThreadName: String get() = Thread.currentThread().name

    @GetMapping
    suspend fun index(): Banner {
        delay(DEFAULT_DELAY)
        return randomBanner()
    }

    @GetMapping("/suspend")
    suspend fun suspendEndpoint(): Banner {
        delay(DEFAULT_DELAY)
        return randomBanner().apply {
            log.debug { "Suspending ... banner=$this, threadName=[$currentThreadName]" }
        }
    }

    @GetMapping("/deferred")
    fun deferredEndpoint(): Deferred<Banner> = CoroutineScope(dispatcher).async {
        delay(DEFAULT_DELAY)
        randomBanner().apply {
            log.debug { "Deferred ... banner=$this, threadName=[$currentThreadName]" }
        }
    }

    @GetMapping("/sequential-flow")
    fun sequentialFlow(
        @RequestParam(
            required = false,
            value = "size",
            defaultValue = "$FLOW_SIZE"
        ) size: Int,
    ): Flow<Banner> {
        log.debug { "Get banners in sequential mode. size=$size" }
        return flow {
            repeat(size) {
                emit(retrieveBanner())
            }
        }
    }

    @GetMapping("/concurrent-flow")
    fun concurrentFlow(
        @RequestParam(
            required = false,
            value = "size",
            defaultValue = "$FLOW_SIZE"
        ) size: Int,
    ): Flow<Banner> {
        log.debug { "Get banners in concurrent mode. size=$size" }
        return (1..size).asFlow()
            .flatMapMerge(size) {
                flow {
                    emit(retrieveBanner())
                }
            }
    }

    @GetMapping("/error")
    suspend fun error() {
        throw RuntimeException("Boom!")
    }

    @PostMapping("/request-as-flow")
    suspend fun requestAsStream(@RequestBody requests: Flow<JsonNode>): Flow<JsonNode> {
        return channelFlow {
            requests
                .onEach {
                    delay(DEFAULT_DELAY)
                    log.debug { "Processing request=${it.toPrettyString()}, threadName=[$currentThreadName]" }
                }
                .collect {
                    send(it)
                }
        }
    }

    private suspend fun retrieveBanner(): Banner {
        delay(DEFAULT_DELAY)
        return randomBanner()
    }
}
