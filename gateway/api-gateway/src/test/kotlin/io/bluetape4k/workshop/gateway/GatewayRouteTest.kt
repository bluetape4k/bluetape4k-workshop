package io.bluetape4k.workshop.gateway

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.shared.web.httpGet
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.returnResult
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

class GatewayRouteTest: AbstractGatewayTest() {

    companion object {
        private val customersServer: DisposableServer =
            HttpServer.create()
                .host("localhost")
                .port(0)
                .route { routes ->
                    routes.get("/api/v1/customers") { _, response ->
                        response
                            .header("Content-Type", "application/json")
                            .sendString(Mono.just("""[{"name":"Winter"},{"name":"Spring"}]"""))
                    }
                }
                .bindNow()

        private val ordersServer: DisposableServer =
            HttpServer.create()
                .host("localhost")
                .port(0)
                .route { routes ->
                    routes.get("/api/v1/orders") { _, response ->
                        response
                            .header("Content-Type", "application/json")
                            .sendString(
                                Mono.just(
                                    """[{"orderNumber":"O-1","amount":100.0,"customerName":"Winter"}]"""
                                )
                            )
                    }
                }
                .bindNow()

        @JvmStatic
        @DynamicPropertySource
        fun gatewayProperties(registry: DynamicPropertyRegistry) {
            registry.add("workshop.gateway.customers-uri") {
                "http://localhost:${customersServer.port()}"
            }
            registry.add("workshop.gateway.orders-uri") {
                "http://localhost:${ordersServer.port()}"
            }
        }

        @JvmStatic
        @AfterAll
        fun closeStubServers() {
            customersServer.disposeNow()
            ordersServer.disposeNow()
        }
    }

    @Test
    fun `gateway rewrites customer service route and adds bluetape4k header`() = runSuspendIO {
        val response = client
            .httpGet("/customer-service/api/v1/customers")
            .expectStatus().is2xxSuccessful
            .expectHeader().valueEquals("X-BLUETAPE4K-API", "BLUETAPE4K.IO")
            .returnResult<String>().responseBody
            .awaitSingle()

        response shouldBeEqualTo """[{"name":"Winter"},{"name":"Spring"}]"""
    }

    @Test
    fun `gateway rewrites order service route and adds bluetape4k header`() = runSuspendIO {
        val response = client
            .httpGet("/order-service/api/v1/orders")
            .expectStatus().is2xxSuccessful
            .expectHeader().valueEquals("X-BLUETAPE4K-API", "BLUETAPE4K.IO")
            .returnResult<String>().responseBody
            .awaitSingle()

        response shouldBeEqualTo """[{"orderNumber":"O-1","amount":100.0,"customerName":"Winter"}]"""
    }

    @Test
    fun `gateway miss response does not expose stacktrace`() = runSuspendIO {
        val response = client
            .httpGet("/missing-route")
            .expectStatus().isNotFound
            .returnResult<String>().responseBody
            .collectList()
            .awaitSingle()
            .joinToString("")

        response.contains("trace").shouldBeFalse()
    }
}
