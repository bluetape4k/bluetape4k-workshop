package io.bluetape4k.workshop.gateway.filter

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class UserRateLimitGatewayFilter: GatewayFilter {

    companion object: KLoggingChannel()

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        log.debug { "사용자별 RateLimit 적용 ... - 구현 중" }
        return chain.filter(exchange)
    }
}
