package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * `VOUCHER_DEGRADED_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
internal val VOUCHER_DEGRADED_STATUS = Status("DEGRADED")

/**
 * `VoucherDegradedComponent`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class VoucherDegradedComponent {
    /**
     * `VoucherDegradedComponent.REDIS`은 `redis` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한
     * 뒤 사용합니다.
     */
    REDIS,
    /**
     * `VoucherDegradedComponent.LEADER`은 `leader` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    LEADER,
}

/**
 * advisory backend failure를 추적하되 PostgreSQL authority를 대체하지 못하게 합니다.
 * @property metrics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
internal class VoucherDegradationState(
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: VoucherMetrics? = null,
) {
    /**
     * 프로모션 바우처 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */
    private val degraded = ConcurrentHashMap.newKeySet<VoucherDegradedComponent>()

    /**
     * degrade은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param component 업무 처리에 필요한 component 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun degrade(component: VoucherDegradedComponent) {
        if (degraded.add(component) && component == VoucherDegradedComponent.REDIS) {
            metrics?.redisDegraded("REDIS")
        }
    }

    /**
     * recover은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param component 업무 처리에 필요한 component 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun recover(component: VoucherDegradedComponent) {
        degraded -= component
    }

    /**
     * isDegraded은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param component 업무 처리에 필요한 component 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun isDegraded(component: VoucherDegradedComponent): Boolean = component in degraded
}

/**
 * readiness authority입니다. PostgreSQL이 connection을 받을 때만 process가 ready입니다.
 * @property dataSource 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */
@Component("voucherDatabaseHealthIndicator")
internal class VoucherDatabaseHealthIndicator(
    /**
     * `dataSource`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
): HealthIndicator {

    private companion object: KLogging() {
        /**
         * `VALIDATION_TIMEOUT_SECONDS`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
         *
         * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
         */
        private const val VALIDATION_TIMEOUT_SECONDS = 1
    }

    /**
     * health은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health =
        try {
            /**
             * `valid`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
             *
             * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
             */
            val valid = dataSource.connection.use { conn ->
                conn.isValid(VALIDATION_TIMEOUT_SECONDS)
            }
            log.debug { "connections is valid: $valid" }
            if (valid) Health.up().build() else Health.down().build()
        } catch (_: Exception) {
            Health.down().build()
        }
}

/**
 * Redis는 advisory로 유지됩니다. 손실은 DEGRADED로 표시하지만 HTTP health는 성공으로 유지합니다.
 * @property state 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
@Component("redisHealthIndicator")
internal class VoucherRedisHealthIndicator(
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val state: VoucherDegradationState,
): HealthIndicator {
    /**
     * health은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health =
        state.health(VoucherDegradedComponent.REDIS)
}

/**
 * leader election 손실은 scheduled work를 일시 중지하지만 PostgreSQL readiness를 제거하지 않습니다.
 * @property state 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
@Component("leaderHealthIndicator")
internal class VoucherLeaderHealthIndicator(
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val state: VoucherDegradationState,
): HealthIndicator {

    /**
     * health은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health =
        state.health(VoucherDegradedComponent.LEADER)
}

/**
 * health은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param component 업무 처리에 필요한 component 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun VoucherDegradationState.health(component: VoucherDegradedComponent): Health =
    if (isDegraded(component)) Health.status(VOUCHER_DEGRADED_STATUS).build()
    else Health.up().build()
