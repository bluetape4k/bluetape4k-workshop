package io.bluetape4k.workshop.coroutines.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpPost
import io.bluetape4k.workshop.coroutines.AbstractCoroutineApplicationTest
import io.bluetape4k.workshop.coroutines.model.Banner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.returnResult
import tools.jackson.databind.node.JsonNodeFactory
import kotlin.time.Duration.Companion.milliseconds

class IOCoroutineControllerTest @Autowired constructor(
    context: ApplicationContext,
): AbstractCoroutineApplicationTest(context) {

    companion object: KLoggingChannel() {
        private const val BASE_PATH = "/controller/io"
    }

    @Test
    fun index() = runSuspendIO {
        client
            .httpGet(BASE_PATH)
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspending() = runSuspendIO {
        client
            .httpGet("$BASE_PATH/suspend")
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendIO {
        client
            .httpGet("$BASE_PATH/deferred")
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential flow`() = runSuspendIO {
        client
            .httpGet("$BASE_PATH/sequential-flow")
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .asFlow()
            .log("Sequential")
            .toList() shouldBeEqualTo listOf(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent flow`() = runSuspendIO {
        client
            .httpGet("$BASE_PATH/concurrent-flow")
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .asFlow()
            .log("Concurrent")
            .toList() shouldBeEqualTo listOf(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun error() = runSuspendIO {
        client
            .httpGet("$BASE_PATH/error")
            .expectStatus().is5xxServerError
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `request as flow`() = runSuspendIO {
        val request = (1..5).asFlow()
            .log("Request")
            .onEach {
                delay(10.milliseconds)
            }
            .map {
                JsonNodeFactory.instance.numberNode(it)
            }

        client
            .httpPost("$BASE_PATH/request-as-flow", request)
            .expectStatus().is2xxSuccessful
            .expectBody<String>().isEqualTo("12345")
    }
}
