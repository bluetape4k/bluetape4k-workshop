package io.bluetape4k.workshop.commerce.voucherpool.fixture

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * deterministic voucher-pool recovery demonstration의 닫힌 catalog입니다.
 * @property slug 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal enum class FixtureScenario(val slug: String) {
    /**
     * `FixtureScenario.REDIS_OUTAGE`은 `redis outage` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤
     * 사용합니다.
     */
    REDIS_OUTAGE("redis-outage"),
    /**
     * `FixtureScenario.BLOOM_FALSE_POSITIVE`은 `bloom false positive` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    BLOOM_FALSE_POSITIVE("bloom-false-positive"),
    /**
     * `FixtureScenario.REVEAL_RESPONSE_LOSS`은 `reveal response loss` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    REVEAL_RESPONSE_LOSS("reveal-response-loss"),
    /**
     * `FixtureScenario.PAUSE_ALLOCATION_RACE`은 `pause allocation race` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    PAUSE_ALLOCATION_RACE("pause-allocation-race"),
    /**
     * `FixtureScenario.REDEEM_REVOKE_RACE`은 `redeem revoke race` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된
     * 전이를 확인한 뒤 사용합니다.
     */
    REDEEM_REVOKE_RACE("redeem-revoke-race"),
    /**
     * `FixtureScenario.WORKER_TAKEOVER`은 `worker takeover` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    WORKER_TAKEOVER("worker-takeover"),
    /**
     * `FixtureScenario.CIPHERTEXT_QUARANTINE`은 `ciphertext quarantine` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    CIPHERTEXT_QUARANTINE("ciphertext-quarantine"),
    /**
     * `FixtureScenario.RESTORE_SMOKE`은 `restore smoke` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된
     * 전이를 확인한 뒤
     * 사용합니다.
     */
    RESTORE_SMOKE("restore-smoke"),
}

/**
 * process-local fixture signal 하나의 serializable snapshot입니다.
 * @property scenario 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property armed 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property consumed 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property claimed 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
internal data class FixtureState(
    /**
     * `scenario`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scenario: FixtureScenario,
    /**
     * `armed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val armed: Boolean,
    /**
     * `consumed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val consumed: Boolean,
    /**
     * `claimed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val claimed: Boolean = false,
): Serializable {

    /**
     * armOnce은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun armOnce(): FixtureState =
        if (armed || claimed || consumed) this else copy(armed = true)

    /**
     * claimOnce은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun claimOnce(): FixtureState =
        if (!armed || claimed || consumed) this else copy(armed = false, claimed = true)

    /**
     * finalizeClaim은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param committed 업무 처리에 필요한 committed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun finalizeClaim(committed: Boolean): FixtureState = when {
        !claimed -> this
        committed -> copy(claimed = false, consumed = true)
        else     -> copy(armed = true, claimed = false, consumed = false)
    }

    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * deterministic failure-path demonstration을 위한 transaction-aware fixture signal입니다.
 *
 * configuration과 arming은 감싸는 database transaction이 commit된 뒤에만 적용됩니다.
 * 각 armed signal은 한 번만 소비할 수 있고 다시 configure될 때까지 consumed 상태로 남습니다.
 * @property jdbcExecutor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
@Profile("test")
internal class VoucherPoolFixtures(
    /**
     * `jdbcExecutor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val jdbcExecutor: VoucherPoolJdbcExecutor,
) {

    private companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val BY_SLUG = FixtureScenario.entries.associateBy(FixtureScenario::slug)
    }
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    private val states = ConcurrentHashMap<String, FixtureSlot>()
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    private val claimTokens = AtomicLong()

    init {
        FixtureScenario.entries.forEach { scenario ->
            states[scenario.slug] = FixtureSlot(FixtureState(scenario, armed = false, consumed = false))
        }
    }

    /**
     * catalog은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun catalog(): List<FixtureScenario> = FixtureScenario.entries

    /**
     * state은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun state(name: String): FixtureState {
        val scenario = requireScenario(name)
        return states[scenario.slug].checkNotNull { "fixture state is missing" }.state
    }

    /**
     * configureAfterCommit은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun configureAfterCommit(name: String) {
        val scenario = requireScenario(name)
        jdbcExecutor.afterCommit {
            states.compute(scenario.slug) { _, current ->
                FixtureSlot(
                    state = FixtureState(scenario, armed = false, consumed = false),
                    generation = (current?.generation ?: 0L) + 1L,
                )
            }
        }
    }

    /**
     * armAfterCommit은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun armAfterCommit(name: String) {
        val scenario = requireScenario(name)
        jdbcExecutor.afterCommit {
            states.compute(scenario.slug) { _, current ->
                val existing = current ?: FixtureSlot(FixtureState(scenario, armed = false, consumed = false))
                existing.copy(state = existing.state.armOnce())
            }
        }
    }

    /**
     * claim은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun claim(name: String): Boolean {
        val scenario = requireScenario(name)
        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "fixture claims require an active transaction synchronization"
        }
        val claimed = AtomicBoolean(false)
        val identity = AtomicReference<ClaimIdentity?>()
        val claimToken = claimTokens.incrementAndGet()

        states.compute(scenario.slug) { _, current ->
            val existing = current ?: FixtureSlot(FixtureState(scenario, armed = false, consumed = false))
            if (existing.state.armed && !existing.state.claimed && !existing.state.consumed) {
                claimed.set(true)
                identity.set(ClaimIdentity(existing.generation, claimToken))
                existing.copy(state = existing.state.claimOnce(), claimToken = claimToken)
            } else {
                existing
            }
        }
        if (claimed.get()) {
            val claimedIdentity = checkNotNull(identity.get())
            TransactionSynchronizationManager.registerSynchronization(
                object: TransactionSynchronization {
                    /**
                     * afterCompletion은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록
                     * 합니다.
                     *
                     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
                     *
                     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
                     */
                    override fun afterCompletion(status: Int) {
                        val committed = status == TransactionSynchronization.STATUS_COMMITTED
                        states.computeIfPresent(scenario.slug) { _, current ->
                            if (current.matches(claimedIdentity)) {
                                current.copy(state = current.state.finalizeClaim(committed), claimToken = null)
                            } else {
                                current
                            }
                        }
                    }
                },
            )
        }
        return claimed.get()
    }

    /**
     * requireScenario은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun requireScenario(name: String): FixtureScenario =
        BY_SLUG[name] ?: throw IllegalArgumentException("unsupported fixture scenario")

}
/**
 * FixtureSlot은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property generation 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property claimToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


private data class FixtureSlot(
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: FixtureState,
    /**
     * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Long = 0L,
    /**
     * `claimToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val claimToken: Long? = null,
) {
    /**
     * matches은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun matches(identity: ClaimIdentity): Boolean =
        generation == identity.generation && claimToken == identity.token
}
/**
 * ClaimIdentity은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property generation 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property token 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


private data class ClaimIdentity(
    /**
     * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Long,
    /**
     * `token`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val token: Long,
)
