package io.bluetape4k.workshop.coroutines.handler

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.coroutines.AbstractCoroutineApplicationTest
import io.bluetape4k.workshop.coroutines.model.Banner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitLast
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.returnResult

class CoroutineHandlerTest: AbstractCoroutineApplicationTest() {

    companion object: KLogging()

    @Test
    fun index() = runSuspendIO {
        client.httpGet("/")
            .returnResult<String>().responseBody
            .awaitLast()
            .shouldNotBeEmpty()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspend() = runSuspendIO {
        client.httpGet("/suspend")
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendIO {
        client.httpGet("/deferred")
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential-flow`() = runSuspendIO {
        client.httpGet("/sequential-flow")
            .returnResult<Banner>().responseBody
            .asFlow()
            .toList() shouldBeEqualTo listOf(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent-flow`() = runSuspendIO {
        client.httpGet("/concurrent-flow")
            .returnResult<Banner>().responseBody
            .asFlow()
            .toList() shouldBeEqualTo listOf(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @Test
    fun error() = runSuspendIO {
        client.httpGet("/error", HttpStatus.INTERNAL_SERVER_ERROR)
    }
}
