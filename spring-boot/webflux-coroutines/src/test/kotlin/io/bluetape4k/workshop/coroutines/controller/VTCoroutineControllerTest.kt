package io.bluetape4k.workshop.coroutines.controller

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.bluetape4k.junit5.coroutines.runSuspendVT
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

class VTCoroutineControllerTest: AbstractCoroutineApplicationTest() {

    companion object: KLogging() {
        private const val BASE_PATH = "/controller/vt"
    }

    @Test
    fun index() = runSuspendVT {
        clientGet("$BASE_PATH/")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Banner>()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspending() = runSuspendVT {
        clientGet("$BASE_PATH/suspend")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendVT {
        clientGet("$BASE_PATH/deferred")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential flow`() = runSuspendVT {
        clientGet("$BASE_PATH/sequential-flow")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBodyList<Banner>()
            .contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent flow`() = runSuspendVT {
        clientGet("$BASE_PATH/concurrent-flow")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBodyList<Banner>()
            .contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun error() = runSuspendVT {
        clientGet("$BASE_PATH/error")
            .exchange()
            .expectStatus().is5xxServerError
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `request as flow`() = runSuspendVT {
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
