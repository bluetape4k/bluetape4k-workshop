package io.bluetape4k.workshop.messaging.kafka.filters

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.trace
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * ROOT path ('/') 를 swagger-ui.html로 redirect 합니다.
 */
@Component
@Order(Int.MAX_VALUE)
class RedirectWebFilter: WebFilter {

    companion object: KLoggingChannel() {
        const val ROOT_PATH = "/"
        const val SWAGGER_PATH = "/swagger-ui.html"
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        log.debug { "Apply RedirectWebFilter" }
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
