package io.bluetape4k.workshop.coroutines.handler

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.coroutines.AbstractCoroutineApplicationTest
import io.bluetape4k.workshop.coroutines.model.Banner
import io.bluetape4k.workshop.shared.web.httpGet
import kotlinx.coroutines.reactive.awaitLast
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBodyList
import org.springframework.test.web.reactive.server.returnResult

class CoroutineHandlerTest: AbstractCoroutineApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    fun index() = runSuspendIO {
        client
            .httpGet("/")
            .expectStatus().is2xxSuccessful
            .returnResult<String>().responseBody
            .awaitLast()
            .shouldNotBeEmpty()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspend() = runSuspendIO {
        client
            .httpGet("/suspend")
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendIO {
        client
            .httpGet("/deferred")
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential-flow`() = runSuspendIO {
        client
            .httpGet("/sequential-flow")
            .expectStatus().is2xxSuccessful
            .expectBodyList<Banner>()
            .returnResult().responseBody
            .shouldNotBeNull() shouldBeEqualTo listOf(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent-flow`() = runSuspendIO {
        client
            .httpGet("/concurrent-flow")
            .expectStatus().is2xxSuccessful
            .expectBodyList<Banner>()
            .returnResult().responseBody
            .shouldNotBeNull() shouldBeEqualTo listOf(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @Test
    fun error() = runSuspendIO {
        client
            .httpGet("/error")
            .expectStatus().is5xxServerError
    }
}
