package io.bluetape4k.workshop.bucket4j.controller

import com.giffing.bucket4j.spring.boot.starter.context.metrics.MetricHandler
import com.giffing.bucket4j.spring.boot.starter.context.metrics.MetricTagResult
import com.giffing.bucket4j.spring.boot.starter.context.metrics.MetricType
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.springframework.stereotype.Component

/**
 * Redis 기반 WebFlux filter가 내보내는 Bucket4j starter metric을 로그로 남깁니다.
 *
 * 이 예제 handler는 sample을 외부 metrics backend에 결합하지 않으면서
 * 워크숍 실행 중 metric을 확인할 수 있게 합니다.
 */
@Component
class DebugMetricHandler : MetricHandler {

    companion object : KLoggingChannel()

    override fun handle(
        type: MetricType,
        name: String,
        tokens: Long,
        tags: MutableList<MetricTagResult>,
    ) {
        val tagsStr = tags.joinToString(", ") { "${it.key}:${it.value}" }
        val message = "type: $type; name: $name; tags: $tagsStr; tokens: $tokens"
        log.debug { message }
    }
}
