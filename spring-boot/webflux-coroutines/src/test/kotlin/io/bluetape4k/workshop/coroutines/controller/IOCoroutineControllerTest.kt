package io.bluetape4k.workshop.coroutines.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.coroutines.AbstractCoroutineApplicationTest
import io.bluetape4k.workshop.coroutines.model.Banner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.body
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList
import tools.jackson.databind.node.JsonNodeFactory

class IOCoroutineControllerTest: AbstractCoroutineApplicationTest() {

    companion object: KLoggingChannel() {
        private const val BASE_PATH = "/controller/io"
    }

    @Test
    fun index() = runSuspendIO {
        client
            .get()
            .uri(BASE_PATH)
            .exchange()
            .expectStatus().isOk
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspending() = runSuspendIO {
        client
            .get()
            .uri("$BASE_PATH/suspend")
            .exchange()
            .expectStatus().isOk
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendIO {
        client
            .get()
            .uri("$BASE_PATH/deferred")
            .exchange()
            .expectStatus().isOk
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential flow`() = runSuspendIO {
        client
            .get()
            .uri("$BASE_PATH/sequential-flow")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<Banner>()
            .contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent flow`() = runSuspendIO {
        client
            .get()
            .uri("$BASE_PATH/concurrent-flow")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<Banner>()
            .contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun error() = runSuspendIO {
        client
            .get()
            .uri("$BASE_PATH/error")
            .exchange()
            .expectStatus().is5xxServerError
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `request as flow`() = runSuspendIO {
        val request = (1..5).asFlow()
            .onEach {
                delay(10)
                log.debug { "request node=$it" }
            }
            .map {
                JsonNodeFactory.instance.numberNode(it)
            }

        client
            .post()
            .uri("$BASE_PATH/request-as-flow")
            .body(request)
            .exchange()
            .expectStatus().isOk
            .expectBody<String>().isEqualTo("12345")
    }
}
