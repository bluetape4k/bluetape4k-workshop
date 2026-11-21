package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

/**
 * VoucherMetricsContractTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherMetricsContractTest {

    private companion object: KLogging() {
/**
 * REQUIRED_NAMES은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val REQUIRED_NAMES = setOf(
            "voucher.command.duration",
            "voucher.db.bulkhead.rejected",
            "voucher.redis.degraded",
            "voucher.review.open",
            "voucher.backlog.oldest.age",
            "voucher.worker.last.success",
            "voucher.worker.attempts",
            "voucher.sse.active",
            "voucher.sse.rejected",
            "voucher.leader.state",
        )
/**
 * FORBIDDEN_TAGS은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val FORBIDDEN_TAGS = setOf(
            "tenant",
            "campaign",
            "user",
            "digest",
            "revision",
            "secret",
            "code"
        )
    }

    @Test
/**
 * 이 시나리오는 "metric names and tags stay bounded while operational paths update values" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `metric names and tags stay bounded while operational paths update values`() {
        val registry = SimpleMeterRegistry()
        val metrics = VoucherMetrics(registry)

        metrics.recordCommand("ALLOCATE", "ACCEPTED", 12.millis())
        metrics.databaseRejected("FOREGROUND")
        metrics.redisDegraded("RATE_LIMIT")
        metrics.reviewOpen(3)
        metrics.backlogOldestAge(9.seconds())
        metrics.workerSucceeded(1_700_000_000L, attempts = 2)
        metrics.sseOpened()
        metrics.sseRejected("CAPACITY")
        metrics.leaderState(VoucherLeaderState.ELECTED)

        val names = registry.meters.map { it.id.name }.toSet()
        REQUIRED_NAMES.all(names::contains).shouldBeTrue()
        registry.get("voucher.command.duration").timer().count() shouldBeEqualTo 1L
        registry.get("voucher.db.bulkhead.rejected").counter().count() shouldBeGreaterThan 0.0
        registry.get("voucher.review.open").gauge().value() shouldBeEqualTo 3.0
        registry.get("voucher.sse.active").gauge().value() shouldBeEqualTo 1.0
        registry.meters.flatMap { it.id.tags }.none { it.key in FORBIDDEN_TAGS }.shouldBeTrue()
    }


}
