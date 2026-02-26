package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.Executors

@RestController
@RequestMapping("/httpbin")
class HttpbinController {

    companion object: KLoggingChannel()

    private val webClientBuilder = WebClient.builder()

    private val webClient: WebClient =
        webClientBuilder
            .baseUrl("https://nghttp2.org/httpbin")
            .defaultHeader("Accept", "application/json")
            .exchangeStrategies(
                ExchangeStrategies.builder()
                    .codecs { configurer ->
                        configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) // 16MB
                    }
                    .build()
            )
            .build()

    /**
     * Virtual Thread 를 사용하는 [Scheduler]
     */
    private val schedulerVT: Scheduler = Schedulers.fromExecutor(Executors.newVirtualThreadPerTaskExecutor())

    /**
     * Virtual Thread 를 사용하는 [ExecutorCoroutineDispatcher]
     */
    private val dispatcherVT: ExecutorCoroutineDispatcher =
        Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

    @GetMapping("/delay/mono/{seconds}")
    fun blockMono(@PathVariable seconds: Int): Mono<String> {
        return webClient.get()
            .uri("/delay/$seconds")
            .retrieve()
            .toBodilessEntity()
            .subscribeOn(schedulerVT)
            .flatMap {
                log.info { "Delaying for $seconds seconds. result status=${it.statusCode}, thread=${Thread.currentThread()}" }
                Mono.just(Thread.currentThread().toString())
            }
    }

    @GetMapping("/delay/suspend/{seconds}")
    suspend fun blockSuspend(@PathVariable seconds: Int): String {
        return withContext(dispatcherVT) {
            val result = webClient.get()
                .uri("/delay/$seconds")
                .retrieve()
                .toBodilessEntity()
                .awaitSingle()

            log.info { "Delaying for $seconds seconds. result status=${result.statusCode}, thread=${Thread.currentThread()}" }

            Thread.currentThread().toString()
        }
    }
}
