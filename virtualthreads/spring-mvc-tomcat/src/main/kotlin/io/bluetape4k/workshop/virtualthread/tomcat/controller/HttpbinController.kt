package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.http.HttpbinHttp2Server
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.scheduler.Schedulers

@RestController
@RequestMapping("/httpbin")
class HttpbinController {
    companion object: KLoggingChannel() {
        val httpbin by lazy { HttpbinHttp2Server.Launcher.httpbinHttp2 }
    }

    private val clientBuilder = WebClient.builder()

    private val client = clientBuilder
        .baseUrl(httpbin.url)
        .build()


    @Operation(method = "GET", description = "Delay for the specified number of seconds")
    @GetMapping("/block/{seconds}")
    fun block(@PathVariable seconds: Int): String {
        val result = client.get()
            .uri("/delay/$seconds")
            .retrieve()
            .toBodilessEntity()
            .subscribeOn(Schedulers.boundedElastic())
            .block()

        log.info { "Delaying for $seconds seconds. result status=${result?.statusCode}, thread=${Thread.currentThread()}" }

        return Thread.currentThread().toString()
    }
}
