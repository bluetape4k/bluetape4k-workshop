package io.bluetape4k.workshop.coroutines.handler

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.coroutines.AbstractCoroutineApplicationTest
import io.bluetape4k.workshop.coroutines.model.Banner
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

class CoroutineHandlerTest: AbstractCoroutineApplicationTest() {

    companion object: KLogging()

    @Test
    fun index() = runTest {
        clientGet("/")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspend() = runSuspendIO {
        clientGet("/suspend")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendIO {
        clientGet("/deferred")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Banner>().isEqualTo(expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential-flow`() = runSuspendIO {
        clientGet("/sequential-flow")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBodyList<Banner>().contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent-flow`() = runSuspendIO {
        clientGet("/concurrent-flow")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBodyList<Banner>().contains(expectedBanner, expectedBanner, expectedBanner, expectedBanner)
    }

    @Test
    fun error() = runSuspendIO {
        clientGet("/error")
            .exchange()
            .expectStatus().is5xxServerError
    }
}
