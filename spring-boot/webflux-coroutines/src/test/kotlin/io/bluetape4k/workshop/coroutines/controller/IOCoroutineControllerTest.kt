package io.bluetape4k.workshop.coroutines.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.coroutines.AbstractCoroutineApplicationTest
import io.bluetape4k.workshop.coroutines.model.Banner
import io.bluetape4k.workshop.shared.web.httpGet
import io.bluetape4k.workshop.shared.web.httpPost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
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
            .httpGet(BASE_PATH)
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspending() = runSuspendIO {
        client
            .httpGet("$BASE_PATH/suspend")
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendIO {
        client
            .httpGet("$BASE_PATH/deferred")
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential flow`() = runSuspendIO {
        client
            .httpGet("$BASE_PATH/sequential-flow")
            .expectBodyList<Banner>()
            .contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent flow`() = runSuspendIO {
        client
            .httpGet("$BASE_PATH/concurrent-flow")
            .expectBodyList<Banner>()
            .contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun error() = runSuspendIO {
        client
            .httpGet("$BASE_PATH/error", HttpStatus.INTERNAL_SERVER_ERROR)
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
            .httpPost("$BASE_PATH/request-as-flow", request)
            .expectBody<String>().isEqualTo("12345")
    }
}
