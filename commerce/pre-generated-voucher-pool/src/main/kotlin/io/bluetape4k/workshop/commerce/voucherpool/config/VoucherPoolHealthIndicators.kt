package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.logging.KLogging
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * `VOUCHER_POOL_DEGRADED_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
internal val VOUCHER_POOL_DEGRADED_STATUS = Status("DEGRADED")
/**
 * `VOUCHER_POOL_RECOVERING_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
internal val VOUCHER_POOL_RECOVERING_STATUS = Status("RECOVERING")

/**
 * `VoucherPoolHealthComponent`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 * @property authoritative 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal enum class VoucherPoolHealthComponent(val authoritative: Boolean) {
    /**
     * `VoucherPoolHealthComponent.POSTGRESQL`은 `postgresql` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    POSTGRESQL(true),
    /**
     * `VoucherPoolHealthComponent.MIGRATION`은 `migration` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    MIGRATION(true),
    /**
     * `VoucherPoolHealthComponent.REFERENCED_KEYS`은 `referenced keys` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    REFERENCED_KEYS(true),
    /**
     * `VoucherPoolHealthComponent.LIFECYCLE`은 `lifecycle` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    LIFECYCLE(true),
    /**
     * `VoucherPoolHealthComponent.QUARANTINE`은 `quarantine` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    QUARANTINE(false),
    /**
     * `VoucherPoolHealthComponent.REDIS`은 `redis` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    REDIS(false),
    /**
     * `VoucherPoolHealthComponent.LEADER`은 `leader` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤
     * 사용합니다.
     */
    LEADER(false),
    /**
     * `VoucherPoolHealthComponent.RECOVERY`은 `recovery` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한
     * 뒤 사용합니다.
     */
    RECOVERY(false),
}

/**
 * `VoucherPoolHealthReason`은 사유 코드입니다.
 * 호출자가 재시도·거절·대사·운영자 조치를 분기하는 오류 계약입니다.
 */
internal enum class VoucherPoolHealthReason {
    /**
     * `VoucherPoolHealthReason.HEALTHY`은 `healthy` 업무 상황을 나타냅니다. 호출자는 이 코드를 재시도·거절·대사·운영자 조치의 분기
     * 기준으로 사용합니다.
     */
    HEALTHY,
    /**
     * `VoucherPoolHealthReason.POSTGRESQL_UNAVAILABLE`은 필수 의존성이 준비되지 않아 요청을 수용할 수 없는 값입니다.
     */
    POSTGRESQL_UNAVAILABLE,
    /**
     * `VoucherPoolHealthReason.MIGRATION_UNAVAILABLE`은 필수 의존성이 준비되지 않아 요청을 수용할 수 없는 값입니다.
     */
    MIGRATION_UNAVAILABLE,
    /**
     * `VoucherPoolHealthReason.REFERENCED_KEY_UNAVAILABLE`은 필수 의존성이 준비되지 않아 요청을 수용할 수 없는 값입니다.
     */
    REFERENCED_KEY_UNAVAILABLE,
    /**
     * `VoucherPoolHealthReason.SHUTTING_DOWN`은 `shutting down` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의
     * 분기 기준으로 사용합니다.
     */
    SHUTTING_DOWN,
    /**
     * `VoucherPoolHealthReason.QUARANTINED_ENTRY`은 `quarantined entry` 업무 상황을 나타냅니다. 호출자는 이 코드를
     * 재시도·거절·대사·운영자 조치의 분기 기준으로 사용합니다.
     */
    QUARANTINED_ENTRY,
    /**
     * `VoucherPoolHealthReason.REDIS_UNAVAILABLE`은 필수 의존성이 준비되지 않아 요청을 수용할 수 없는 값입니다.
     */
    REDIS_UNAVAILABLE,
    /**
     * `VoucherPoolHealthReason.LEADER_UNAVAILABLE`은 필수 의존성이 준비되지 않아 요청을 수용할 수 없는 값입니다.
     */
    LEADER_UNAVAILABLE,
    /**
     * `VoucherPoolHealthReason.RECOVERY_IN_PROGRESS`은 비동기 worker가 처리 중이라 최종 결과를 아직 확정할 수 없는 값입니다.
     */
    RECOVERY_IN_PROGRESS,
}

/**
 * `VoucherPoolHealthLevel`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class VoucherPoolHealthLevel {
    /**
     * `VoucherPoolHealthLevel.UP`은 `up` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    UP,
    /**
     * `VoucherPoolHealthLevel.RECOVERING`은 `recovering` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한
     * 뒤 사용합니다.
     */
    RECOVERING,
    /**
     * `VoucherPoolHealthLevel.DEGRADED`은 보조 의존성이 제한되지만 권위 데이터만으로 일부 기능을 계속할 수 있는 값입니다.
     */
    DEGRADED,
    /**
     * `VoucherPoolHealthLevel.DOWN`은 `down` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    DOWN,
}
/**
 * VoucherPoolHealthSnapshot은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property level 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reason 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property lastSuccessAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 * @property lastErrorAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 */


internal class VoucherPoolHealthSnapshot(
    /**
     * `level`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val level: VoucherPoolHealthLevel,
    /**
     * `reason`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reason: VoucherPoolHealthReason,
    /**
     * `lastSuccessAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val lastSuccessAt: Instant,
    /**
     * `lastErrorAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val lastErrorAt: Instant?,
)

/**
 * health reason을 allowlist로 제한하고 exception message나 tenant data와 독립적으로 유지합니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
internal class VoucherPoolHealthState(
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock = Clock.systemUTC(),
) {

    companion object: KLogging()
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    private val states = ConcurrentHashMap<VoucherPoolHealthComponent, ComponentState>()
    /**
     * 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */

    @Volatile
    private var lastSuccessAt = Instant.now(clock)
    /**
     * 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
     */

    @Volatile
    private var lastErrorAt: Instant? = null

    /**
     * fail은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param component 업무 처리에 필요한 component 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun fail(component: VoucherPoolHealthComponent, reason: VoucherPoolHealthReason) {
        require(component.authoritative) { "$component is advisory and cannot make readiness DOWN" }
        update(component, VoucherPoolHealthLevel.DOWN, reason)
    }

    /**
     * degrade은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param component 업무 처리에 필요한 component 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun degrade(component: VoucherPoolHealthComponent, reason: VoucherPoolHealthReason) {
        require(!component.authoritative) { "$component is authoritative and cannot be merely DEGRADED" }
        update(component, VoucherPoolHealthLevel.DEGRADED, reason)
    }

    /**
     * recovering은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun recovering(reason: VoucherPoolHealthReason = VoucherPoolHealthReason.RECOVERY_IN_PROGRESS) {
        update(VoucherPoolHealthComponent.RECOVERY, VoucherPoolHealthLevel.RECOVERING, reason)
    }

    /**
     * recover은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param component 업무 처리에 필요한 component 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun recover(component: VoucherPoolHealthComponent) {
        states -= component
        lastSuccessAt = Instant.now(clock)
    }

    /**
     * snapshot은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun snapshot(): VoucherPoolHealthSnapshot {
        val current = states.values
            .maxWithOrNull(compareBy<ComponentState> { it.level.ordinal }.thenBy { it.reason.ordinal })

        return VoucherPoolHealthSnapshot(
            level = current?.level ?: VoucherPoolHealthLevel.UP,
            reason = current?.reason ?: VoucherPoolHealthReason.HEALTHY,
            lastSuccessAt = lastSuccessAt,
            lastErrorAt = lastErrorAt,
        )
    }

    /**
     * update은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param component 업무 처리에 필요한 component 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param level 업무 처리에 필요한 level 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun update(
        component: VoucherPoolHealthComponent,
        level: VoucherPoolHealthLevel,
        reason: VoucherPoolHealthReason,
    ) {
        require(reason != VoucherPoolHealthReason.HEALTHY) { "failure reason must be explicit" }
        val now = Instant.now(clock)
        states[component] = ComponentState(level, reason)
        lastErrorAt = now
    }
    /**
     * ComponentState은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property level 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     * @property reason 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */


    private class ComponentState(
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val level: VoucherPoolHealthLevel,
        /**
         * `reason`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val reason: VoucherPoolHealthReason,
    )
}

/**
 * PostgreSQL과 필수 migration/key state가 readiness authority입니다.
 * @property dataSource 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property state 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */
@Component("voucherPoolReadinessHealthIndicator")
internal class VoucherPoolReadinessHealthIndicator(
    /**
     * `dataSource`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val dataSource: DataSource,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val state: VoucherPoolHealthState,
): HealthIndicator {

    private companion object: KLogging() {
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        const val VALIDATION_TIMEOUT_SECONDS = 1
    }

    /**
     * health은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health {
        validatePostgres()
        val snapshot = state.snapshot()
        val builder =
            when (snapshot.level) {
                VoucherPoolHealthLevel.UP       -> Health.up()
                VoucherPoolHealthLevel.RECOVERING -> Health.status(VOUCHER_POOL_RECOVERING_STATUS)
                VoucherPoolHealthLevel.DEGRADED -> Health.status(VOUCHER_POOL_DEGRADED_STATUS)
                VoucherPoolHealthLevel.DOWN     -> Health.down()
            }
        builder.withDetail("reason", snapshot.reason.name)
            .withDetail("lastSuccessAt", snapshot.lastSuccessAt.toString())
        snapshot.lastErrorAt?.let { builder.withDetail("lastErrorAt", it.toString()) }
        return builder.build()
    }

    /**
     * validatePostgres은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun validatePostgres() {
        val valid = try {
            dataSource.connection.use { it.isValid(VALIDATION_TIMEOUT_SECONDS) }
        } catch (_: Exception) {
            false
        }

        if (valid) {
            state.recover(VoucherPoolHealthComponent.POSTGRESQL)
        } else {
            state.fail(
                VoucherPoolHealthComponent.POSTGRESQL,
                VoucherPoolHealthReason.POSTGRESQL_UNAVAILABLE
            )
        }
    }
}

/** liveness는 process-only입니다. dependency degradation은 readiness에 속합니다. */
/** liveness는 process-only입니다. dependency degradation은 readiness에 속합니다. */
@Component("voucherPoolLivenessHealthIndicator")
internal class VoucherPoolLivenessHealthIndicator: HealthIndicator {
    /**
     * health은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun health(): Health =
        Health
            .up()
            .withDetail("reason", "PROCESS_RUNNING")
            .build()
}
