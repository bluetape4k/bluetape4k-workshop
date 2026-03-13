package io.bluetape4k.workshop.micrometer.zipkin

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.infra.ZipkinServer
import io.bluetape4k.utils.Systemx
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import kotlin.time.Duration.Companion.seconds

class ZipkinServerLaunchTest {

    companion object: KLoggingChannel()

    private val zipkin by lazy { ZipkinServer.Launcher.zipkin }
    private val webClient by lazy { WebClient.create() }

    @Disabled("Zipkin 서버 접속 시 예외 발생 : not on SSL/TLS record:")
    @Test
    fun `launch zipkin server`() = runTest(timeout = 30.seconds) {
        zipkin.start()
        zipkin.isRunning.shouldBeTrue()

        val zipkinUrl = Systemx.getProp("testcontainers.zipkin.url")
        log.debug { "zipkinUrl=$zipkinUrl" }
        zipkinUrl.shouldNotBeNull() shouldBeEqualTo zipkin.url

        val client = WebClient.builder().baseUrl(zipkinUrl).build()

        val response = client.get()
            .uri("/zipkin/")
            .retrieve()
            .bodyToMono<String>()
            .awaitSingleOrNull()

        log.debug { "response=$response" }
        response.shouldNotBeNull()
    }
}
