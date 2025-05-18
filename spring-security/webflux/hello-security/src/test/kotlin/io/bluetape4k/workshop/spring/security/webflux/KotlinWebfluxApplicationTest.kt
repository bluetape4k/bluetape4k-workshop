package io.bluetape4k.workshop.spring.security.webflux

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

@SpringBootTest
class KotlinWebfluxApplicationTest {

    companion object: KLoggingChannel()

    lateinit var client: WebTestClient

    @Autowired
    fun setup(context: ApplicationContext) {
        this.client = WebTestClient
            .bindToApplicationContext(context)
            .configureClient()
            .build()
    }

    @Test
    fun `index page is not protected`() = runTest {
        val response = client.httpGet("/")
            .returnResult<String>()
            .responseBody
            .asFlow()
            .toList()
            .joinToString("\n")

        log.debug { "Response: $response" }
        response shouldContain "This is an unsecured page"
    }

    @Test
    fun `protected page when unauthenticated then redirects to login`() = runTest {
        client.get()
            .uri("/user/index")
            .exchange()
            .expectStatus().is3xxRedirection
            .expectHeader().valueEquals("Location", "/log-in")
    }

    @Test
    @WithMockUser
    fun `protected page can be accessed when authenticated`() = runTest {
        val response = client.httpGet("/user/index")
            .returnResult<String>()
            .responseBody
            .asFlow()
            .toList()
            .joinToString("\n")

        log.debug { "Response: $response" }
        response shouldContain "This is a secured page"
    }

}
