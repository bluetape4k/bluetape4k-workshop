package io.bluetape4k.workshop.gateway.filter

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.gateway.RateLimitHeaders
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class UserKeyResolver: KeyResolver {

    companion object: KLogging()

    override fun resolve(exchange: ServerWebExchange): Mono<String> {
        val userKey = exchange.request.headers.getFirst(RateLimitHeaders.X_BLUETAPE4K_UID)
            ?: exchange.request.remoteAddress?.address?.hostAddress
            ?: "anonymous"

        log.debug { "Resolve user key for Rate limiter: $userKey" }
        return Mono.just(userKey)
    }
}
