package io.bluetape4k.workshop.commerce.ticket.config

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
/**
 * TicketMigrationHealthIndicator은 콘서트 티켓 판매에서 사용하는 업무 객체입니다.
 * @property readiness 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


class TicketMigrationHealthIndicator(private val readiness: TicketMigrationReadiness): HealthIndicator {
    /**
     * health은 콘서트 티켓 판매의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health = if (readiness.isReady) Health.up().build() else Health.outOfService().build()
}

/**
 * `TicketRedisHealthProbe`은 콘서트 티켓 플래시 세일 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
fun interface TicketRedisHealthProbe {
    /**
     * ping은 콘서트 티켓 판매의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun ping(): String
}
/**
 * TicketRedisHealthIndicator은 콘서트 티켓 판매에서 사용하는 업무 객체입니다.
 * @property probe 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


class TicketRedisHealthIndicator(private val probe: TicketRedisHealthProbe): HealthIndicator {
    /**
     * health은 콘서트 티켓 판매의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health = runCatching { probe.ping() }
        .fold(
            onSuccess = { Health.up().build() },
            onFailure = { Health.outOfService().withDetail("code", "redis_unavailable").build() },
        )
}

/** liveness는 Redis와 downstream provider를 의도적으로 제외합니다. */
/** liveness는 Redis와 downstream provider를 의도적으로 제외합니다. */
class TicketLivenessHealthIndicator: HealthIndicator {
    /**
     * health은 콘서트 티켓 판매의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health = Health.up().build()
}
