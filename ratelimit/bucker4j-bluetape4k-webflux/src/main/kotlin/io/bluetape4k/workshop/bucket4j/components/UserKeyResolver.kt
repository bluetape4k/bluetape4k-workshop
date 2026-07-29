package io.bluetape4k.workshop.bucket4j.components

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.bucket4j.utils.HeaderUtils
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange

@Component
class UserKeyResolver : KeyResolver<String> {

    companion object : KLoggingChannel()

    /**
     * [HeaderUtils.X_BLUETAPE4K_UID]에서 rate-limit key를 해석하고,
     * header가 없으면 remote host 문자열을 fallback으로 사용합니다.
     *
     * @param exchange 현재 요청과 응답을 담은 [ServerWebExchange]입니다.
     * @return 사용자별 key입니다. 사용할 수 있는 key source가 없으면 `null`입니다.
     */
    override fun resolve(exchange: ServerWebExchange): String? {
        return exchange.request.headers.getFirst(HeaderUtils.X_BLUETAPE4K_UID)
            ?: exchange.request.remoteAddress?.hostString
    }
}
