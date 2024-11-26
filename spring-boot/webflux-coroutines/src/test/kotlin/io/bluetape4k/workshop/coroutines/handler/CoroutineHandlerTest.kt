package io.bluetape4k.workshop.coroutines.handler

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
    fun suspend() = runTest {
        clientGet("/suspend")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Banner>().isEqualTo(banner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runTest {
        clientGet("/deferred")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Banner>().isEqualTo(banner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential-flow`() = runTest {
        clientGet("/sequential-flow")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBodyList<Banner>().contains(banner, banner, banner, banner)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent-flow`() = runTest {
        clientGet("/concurrent-flow")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBodyList<Banner>().contains(banner, banner, banner, banner)
    }

    @Test
    fun error() = runTest {
        clientGet("/error")
            .exchange()
            .expectStatus().is5xxServerError
    }
}
