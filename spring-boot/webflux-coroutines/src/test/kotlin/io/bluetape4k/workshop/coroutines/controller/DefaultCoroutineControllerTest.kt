package io.bluetape4k.workshop.coroutines.controller

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.bluetape4k.junit5.coroutines.runSuspendDefault
import io.bluetape4k.logging.KLogging
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

class DefaultCoroutineControllerTest: AbstractCoroutineApplicationTest() {

    companion object: KLogging() {
        private const val BASE_PATH = "/controller/default"
    }

    @Test
    fun index() = runSuspendDefault {
        clientGet("$BASE_PATH/")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Banner>()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspending() = runSuspendDefault {
        clientGet("$BASE_PATH/suspend")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendDefault {
        clientGet("$BASE_PATH/deferred")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential flow`() = runSuspendDefault {
        clientGet("$BASE_PATH/sequential-flow")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBodyList<Banner>()
            .contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent flow`() = runSuspendDefault {
        clientGet("$BASE_PATH/concurrent-flow")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBodyList<Banner>()
            .contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun error() = runSuspendDefault {
        clientGet("$BASE_PATH/error")
            .exchange()
            .expectStatus().is5xxServerError
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `request as flow`() = runSuspendDefault {
        val request = (1..5).asFlow()
            .onEach {
                delay(10)
                log.debug { "request node=$it" }
            }
            .map {
                JsonNodeFactory.instance.numberNode(it)
            }

        clientPost("$BASE_PATH/request-as-flow")
            .body(request)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>().isEqualTo("12345")
    }
}
