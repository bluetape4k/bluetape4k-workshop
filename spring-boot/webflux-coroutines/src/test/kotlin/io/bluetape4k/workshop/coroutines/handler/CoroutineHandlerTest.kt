package io.bluetape4k.workshop.coroutines.handler

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
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
import org.springframework.test.web.reactive.server.returnResult

class CoroutineHandlerTest: AbstractCoroutineApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    fun index() = runSuspendIO {
        client
            .get()
            .uri("/")
            .exchange()
            .expectStatus().isOk
            .returnResult<String>().responseBody
            .awaitLast()
            .shouldNotBeEmpty()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspend() = runSuspendIO {
        client
            .get()
            .uri("/suspend")
            .exchange()
            .expectStatus().isOk
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendIO {
        client.get()
            .uri("/deferred")
            .exchange()
            .expectStatus().isOk
            .returnResult<Banner>().responseBody
            .awaitSingle() shouldBeEqualTo expectedBanner
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential-flow`() = runSuspendIO {
        client
            .get()
            .uri("/sequential-flow")
            .exchange()
            .expectStatus().isOk
            .returnResult<Banner>().responseBody
            .asFlow()
            .toList() shouldBeEqualTo listOf(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent-flow`() = runSuspendIO {
        client
            .get()
            .uri("/concurrent-flow")
            .exchange()
            .expectStatus().isOk
            .returnResult<Banner>().responseBody
            .asFlow()
            .toList() shouldBeEqualTo listOf(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @Test
    fun error() = runSuspendIO {
        client
            .get()
            .uri("/error")
            .exchange()
            .expectStatus().is5xxServerError
    }
}
