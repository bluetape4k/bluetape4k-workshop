package io.bluetape4k.workshop.coroutines.controller

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.bluetape4k.junit5.coroutines.runSuspendDefault
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpPost
import io.bluetape4k.workshop.coroutines.AbstractCoroutineApplicationTest
import io.bluetape4k.workshop.coroutines.model.Banner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

class DefaultCoroutineControllerTest: AbstractCoroutineApplicationTest() {

    companion object: KLoggingChannel() {
        private const val BASE_PATH = "/controller/default"
    }

    @Test
    fun index() = runSuspendDefault {
        client.httpGet("$BASE_PATH/")
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspending() = runSuspendDefault {
        client.httpGet("$BASE_PATH/suspend")
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendDefault {
        client.httpGet("$BASE_PATH/deferred")
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential flow`() = runSuspendDefault {
        client.httpGet("$BASE_PATH/sequential-flow")
            .expectBodyList<Banner>()
            .contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent flow`() = runSuspendDefault {
        client.httpGet("$BASE_PATH/concurrent-flow")
            .expectBodyList<Banner>()
            .contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun error() = runSuspendDefault {
        client.httpGet("$BASE_PATH/error", HttpStatus.INTERNAL_SERVER_ERROR)
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

        client.httpPost("$BASE_PATH/request-as-flow", request)
            .expectBody<String>().isEqualTo("12345")
    }
}
