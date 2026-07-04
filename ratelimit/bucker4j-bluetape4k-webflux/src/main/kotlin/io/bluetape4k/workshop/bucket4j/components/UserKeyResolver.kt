package io.bluetape4k.workshop.bucket4j.components

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.bucket4j.utils.HeaderUtils
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange

@Component
class UserKeyResolver : KeyResolver<String> {

    companion object : KLoggingChannel()

    /**
     * Resolves a rate-limit key from [HeaderUtils.X_BLUETAPE4K_UID], falling back to the
     * remote host string when the header is absent.
     *
     * @param exchange Current [ServerWebExchange].
     * @return User-specific key, or `null` when no key source is available.
     */
    override fun resolve(exchange: ServerWebExchange): String? {
        return exchange.request.headers.getFirst(HeaderUtils.X_BLUETAPE4K_UID)
            ?: exchange.request.remoteAddress?.hostString
    }
}
