package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

/**
 * moderation workflow metric이 사용자별/raw provider 값으로 cardinality를
 * 폭발시키지 않는지 검증합니다. upload content type, transition/cleanup 결과,
 * moderation 결과 같은 bounded label만 허용해 운영 대시보드가 예측 가능하도록 합니다.
 */
class ProfileImageMetricsTest {

    companion object: KLogging()

    @Test
/**
 * metrics_use_low_cardinality_tags은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun metrics_use_low_cardinality_tags() {
        val registry = SimpleMeterRegistry()
        val metrics = ProfileImageMetrics(registry)

        metrics.uploadAccepted("image/jpeg")
        metrics.transition("approved", "approved")
        metrics.cleanup("deleted")
        metrics.moderation(1_000_000, "approved")

        registry.meters
            .flatMap { it.id.tags }
            .map { it.key }
            .toSet() shouldBeEqualTo setOf("contentType", "status", "result")

        registry.counter(
            ProfileImageMetrics.UPLOAD_ACCEPTED,
            "contentType", "image/jpeg"
        ).count() shouldBeEqualTo 1.0
    }
}
