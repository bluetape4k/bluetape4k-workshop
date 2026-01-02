package io.bluetape4k.workshop.spring.security.webflux.jwt.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.spring.security.webflux.jwt.AbstractJwtApplicationTest
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.reactive.server.returnResult

class HelloControllerTest: AbstractJwtApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        webTestClient.shouldNotBeNull()
    }

    // NOTE: "이렇게 plain password 를 전달하면, password encoder 설정을 막아야 하기 때문에 @WithMockUser 를 사용하는 것을 추천합니다"
    @Test
    fun `인증된 user가 새로운 토큰 발급을 요청하면 발급되고 인증되어야 한다`() = runTest {
        // 인증 정보로 토큰을 발급 받는다 
        val token = webTestClient.post()
            .uri("/token")
            .headers {
                it.setBasicAuth("user", "password")
            }
            .exchange()
            .expectStatus().isOk
            .returnResult<String>().responseBody
            .awaitSingle()

        token.shouldNotBeNull()
        log.debug { "token=$token" }

        // 발급받은 토큰으로 서버에 접근해야 한다
        val result = webTestClient.get()
            .uri("/")
            .headers {
                it.setBearerAuth(token)
            }
            .exchange()
            .expectStatus().isOk
            .returnResult<String>().responseBody
            .awaitSingle()

        result shouldBeEqualTo "Hello, user!"
    }

    @Test
    @WithMockUser
    fun `MockUser로 인증된 사용자는 서버 접근이 되어야 합니다`() = runTest {
        // 인증 정보로 토큰을 발급 받는다
        val token = webTestClient.post()
            .uri("/token")
            .exchange()
            .expectStatus().isOk
            .returnResult<String>().responseBody
            .awaitSingle()

        token.shouldNotBeNull()
        log.debug { "token=$token" }

        // 발급받은 토큰으로 서버에 접근해야 한다
        val result = webTestClient.get()
            .uri("/")
            .headers {
                it.setBearerAuth(token)
            }
            .exchange()
            .expectStatus().isOk
            .returnResult<String>().responseBody
            .awaitSingle()

        result shouldBeEqualTo "Hello, user!"
    }

    @Test
    fun `인증 안된 user가 새로운 토큰 발급을 요청하면 인증 예외가 발생합니다`() {
        webTestClient.post()
            .uri("/token")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `인증 안된 사용자는 서버 접근이 안되어야 합니다`() = runTest {
        webTestClient.post()
            .uri("/")
            .exchange()
            .expectStatus().isUnauthorized

    }
}
