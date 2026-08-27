package io.bluetape4k.workshop.spring.security.webflux

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import org.junit.jupiter.api.Test
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.reactive.server.expectBody

class MainControllerTest : AbstractSecurityApplicationTest() {

    companion object : KLoggingChannel()

    @Test
    fun `index page is not protected`() = runSuspendIO {
        val response = client
            .httpGet("/")
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .shouldNotBeNull()

        log.debug { "Response: $response" }
        response shouldContain "This is an unsecured page"
    }

    @Test
    fun `protected page when unauthenticated then redirects to login`() = runSuspendIO {
        client
            .httpGet("/user/index")
            .expectStatus().is3xxRedirection
            .expectHeader().valueEquals("Location", "/log-in")
    }

    @Test
    @WithMockUser
    fun `protected page can be accessed when authenticated`() = runSuspendIO {
        val response = client
            .httpGet("/user/index")
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .shouldNotBeNull()

        log.debug { "Response: $response" }
        response shouldContain "This is a secured page"
    }
}
