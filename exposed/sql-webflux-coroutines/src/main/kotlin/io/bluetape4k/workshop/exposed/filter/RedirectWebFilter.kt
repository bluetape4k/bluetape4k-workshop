package io.bluetape4k.workshop.exposed.filter

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.trace
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * ROOT path ('/') 를 swagger-ui.html로 redirect 합니다.
 */
@Component
class RedirectWebFilter: WebFilter {

    companion object: KLogging() {
        const val ROOT_PATH = "/"
        const val SWAGGER_PATH = "/swagger-ui.html"
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val redirectExchange = when (exchange.request.uri.path) {
            ROOT_PATH -> {
                log.trace { "redirect to `$SWAGGER_PATH`" }
                exchange.mutate().request(exchange.request.mutate().path(SWAGGER_PATH).build()).build()
            }

            else      -> exchange
        }
        return chain.filter(redirectExchange)
    }
}
