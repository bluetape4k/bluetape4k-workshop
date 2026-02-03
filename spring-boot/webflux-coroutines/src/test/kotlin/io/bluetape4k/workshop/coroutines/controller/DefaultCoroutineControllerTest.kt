package io.bluetape4k.workshop.coroutines.controller

import io.bluetape4k.coroutines.flow.extensions.toFastList
import io.bluetape4k.junit5.coroutines.runSuspendDefault
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
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.returnResult
import tools.jackson.databind.node.JsonNodeFactory

class DefaultCoroutineControllerTest: AbstractCoroutineApplicationTest() {

    companion object: KLoggingChannel() {
        private const val BASE_PATH = "/controller/default"
    }

    @Test
    fun index() = runSuspendDefault {
        client
            .httpGet(BASE_PATH)
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspending() = runSuspendDefault {
        client
            .httpGet("$BASE_PATH/suspend")
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendDefault {
        client
            .httpGet("$BASE_PATH/deferred")
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential flow`() = runSuspendDefault {
        client
            .httpGet("$BASE_PATH/sequential-flow")
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .asFlow()
            .toFastList() shouldBeEqualTo listOf(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent flow`() = runSuspendDefault {
        client
            .httpGet("$BASE_PATH/concurrent-flow")
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .asFlow()
            .toFastList() shouldBeEqualTo listOf(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun error() = runSuspendDefault {
        client
            .httpGet("$BASE_PATH/error")
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

        client
            .httpPost("$BASE_PATH/request-as-flow", request)
            .expectBody<String>().isEqualTo("12345")

    }
}
