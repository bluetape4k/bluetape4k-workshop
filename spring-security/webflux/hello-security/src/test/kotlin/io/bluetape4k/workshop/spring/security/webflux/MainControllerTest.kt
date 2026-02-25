package io.bluetape4k.workshop.spring.security.webflux

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.shared.web.httpGet
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.reactive.server.expectBody

class MainControllerTest: AbstractSecurityApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    fun `index page is not protected`() = runTest {
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
    fun `protected page when unauthenticated then redirects to login`() = runTest {
        client
            .httpGet("/user/index")
            .expectStatus().is3xxRedirection
            .expectHeader().valueEquals("Location", "/log-in")
    }

    @Test
    @WithMockUser
    fun `protected page can be accessed when authenticated`() = runTest {
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
