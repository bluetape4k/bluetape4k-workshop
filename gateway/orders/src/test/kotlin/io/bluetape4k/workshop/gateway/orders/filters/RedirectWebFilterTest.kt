package io.bluetape4k.workshop.gateway.orders.filters

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class RedirectWebFilterTest {

    @Test
    fun `root path is rewritten to swagger ui`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build())
        var rewrittenPath: String? = null
        val chain = WebFilterChain { rewrittenExchange ->
            rewrittenPath = rewrittenExchange.request.uri.path
            Mono.empty()
        }

        RedirectWebFilter().filter(exchange, chain).block()

        rewrittenPath shouldBeEqualTo "/swagger-ui.html"
    }
}
