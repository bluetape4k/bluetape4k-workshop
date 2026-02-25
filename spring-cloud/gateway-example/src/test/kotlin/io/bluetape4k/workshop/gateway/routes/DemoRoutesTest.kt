package io.bluetape4k.workshop.gateway.routes

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.gateway.GatewayApplicationTest
import io.bluetape4k.workshop.gateway.RateLimitHeaders
import io.bluetape4k.workshop.shared.web.httpGet
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.expectBody


class DemoRoutesTest: GatewayApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @Test
    fun `call root path`() {
        client
            .httpGet("/")
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.name").isEqualTo("Gateway Application")
    }

    @Test
    fun `단순 Path Route 기능 - nghttp2를 사용`() {
        client
            .httpGet("/get")
            .expectStatus().is2xxSuccessful
            .expectBody<Map<*, *>>()
            .consumeWith {
                it.responseBody!!["url"].toString() shouldBeEqualTo "https://nghttp2.org/httpbin/get"
            }
    }

    @Test
    fun `header에 host를 지정하여 routes 하기`() {
        client.get()
            .uri("/headers")
            .header("Host", "www.myhost.org")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Map<*, *>>().consumeWith {
                it.responseBody!!.forEach { (key, value) ->
                    log.debug { "key=$key, value=$value" }
                }
                val headers = it.responseBody!!["headers"] as Map<*, *>
                // headers["X-Forwarded-Host"] shouldBeEqualTo "www.myhost.org"
                headers["Host"] shouldBeEqualTo "nghttp2.org"
            }
    }

    @Test
    fun `요청 경로를 변경하여 route 하기`() {
        // /foo/get -> /get
        // 보통 내부 서비스에서는 /api/v1 등이 붙게 된다. 이렇게 path를 변경해서 호출하는 것을 rewrite라고 한다.
        client.get()
            .uri("/foo/get")
            .header("Host", "www.rewrite.org")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Map<*, *>>().consumeWith {
                it.responseBody!!["url"].toString() shouldBeEqualTo "https://nghttp2.org/httpbin/get"
            }
    }

    @Test
    fun `circuit breaker 를 적용한 route`() {
        // Circuit Breaker 가 적용되어 3초 동안 응답이 없으면 GATEWAY_TIMEOUT(504) 을 반환한다.
        client.get()
            .uri("/delay/3")
            .header("Host", "www.circuitbreaker.org")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
    }

    @Test
    fun `circuit breaker 적용 시 fallback으로 redirect`() {
        client.get()
            .uri("/delay/3")
            .header("Host", "www.circuitbreakerfallback.org")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>().consumeWith {
                it.responseBody!! shouldBeEqualTo "Fallback for circuit breaker"
            }
    }

    @Test
    fun `rate limit per user id`() {
        // NOTE: 이 방식보다 Bucket4j 방식이 더 좋다.
        // 1. 여러가지 Rate Limit 을 적용이 가능하다
        // 2. Path 별로 따로 Rate Limit 을 적용할 수 있다.

        // 1초에 2번만 호출 가능 (defaultBurstCapacity=2)
        repeat(2) {
            client.get()
                .uri("/anything/1")
                .header("Host", "www.limited.org")
                .header(RateLimitHeaders.X_BLUETAPE4K_UID, "debop")
                .exchange()
            // .expectStatus().isOk
        }

        // 혹시 몰라서 한번 더 호출 한다.
        client.get()
            .uri("/anything/1")
            .header("Host", "www.limited.org")
            .header(RateLimitHeaders.X_BLUETAPE4K_UID, "debop")
            .exchange()

        // 3번째 호출은 429 Too Many Requests 를 반환한다.
        client.get()
            .uri("/anything/1")
            .header("Host", "www.limited.org")
            .header(RateLimitHeaders.X_BLUETAPE4K_UID, "debop")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }
}
