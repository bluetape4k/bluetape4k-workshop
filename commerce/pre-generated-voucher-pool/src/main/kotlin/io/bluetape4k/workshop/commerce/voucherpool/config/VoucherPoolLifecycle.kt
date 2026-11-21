package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.requireZeroOrPositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * `VoucherPoolShutdownEvent`은 업무 분기용 제한된 값 집합입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class VoucherPoolShutdownEvent {
    /**
     * `VoucherPoolShutdownEvent.READINESS_DOWN`은 `readiness down` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    READINESS_DOWN,
    /**
     * `VoucherPoolShutdownEvent.COMMANDS_AND_SSE_REJECTED`은 `commands and sse rejected` 업무 상황을
     * 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    COMMANDS_AND_SSE_REJECTED,
    /**
     * `VoucherPoolShutdownEvent.TRIGGERS_STOPPED`은 `triggers stopped` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    TRIGGERS_STOPPED,
    /**
     * `VoucherPoolShutdownEvent.CLAIMS_STOPPED`은 `claims stopped` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    CLAIMS_STOPPED,
    /**
     * `VoucherPoolShutdownEvent.TRANSACTION_DRAIN`은 `transaction drain` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    TRANSACTION_DRAIN,
    /**
     * `VoucherPoolShutdownEvent.SSE_AND_POLLER_CLOSED`은 `sse and poller closed` 업무 상황을 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    SSE_AND_POLLER_CLOSED,
    /**
     * `VoucherPoolShutdownEvent.ADVISORY_RESOURCES_CLOSED`은 `advisory resources closed` 업무 상황을
     * 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    ADVISORY_RESOURCES_CLOSED,
    /**
     * `VoucherPoolShutdownEvent.EXECUTOR_CLOSED`은 `executor closed` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    EXECUTOR_CLOSED,
    /**
     * `VoucherPoolShutdownEvent.DATASOURCE_CLOSED`은 `datasource closed` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    DATASOURCE_CLOSED,
}

/**
 * `VoucherPoolShutdownReason`은 사유 코드입니다.
 * 호출자가 재시도·거절·대사·운영자 조치를 분기하는 오류 계약입니다.
 */
internal enum class VoucherPoolShutdownReason {
    /**
     * `VoucherPoolShutdownReason.CLEAN`은 `clean` 업무 상황을 나타냅니다. 호출자는 이 코드를 재시도·거절·대사·운영자 조치의 분기
     * 기준으로 사용합니다.
     */
    CLEAN,
    /**
     * `VoucherPoolShutdownReason.TRANSACTION_DRAIN_TIMEOUT`은 허용된 시간 예산을 초과해 결과가 미확정인 값입니다. 재시도 전
     * 중복 side
     * effect 여부를 확인해야 합니다.
     */
    TRANSACTION_DRAIN_TIMEOUT,
    /**
     * `VoucherPoolShutdownReason.PHASE_TIMEOUT`은 허용된 시간 예산을 초과해 결과가 미확정인 값입니다. 재시도 전 중복 side
     * effect 여부를
     * 확인해야 합니다.
     */
    PHASE_TIMEOUT,
    /**
     * `VoucherPoolShutdownReason.PHASE_FAILED`은 업무 처리가 실패해 결과를 성공으로 간주할 수 없는 값입니다.
     */
    PHASE_FAILED,
    /**
     * `VoucherPoolShutdownReason.TOTAL_TIMEOUT`은 허용된 시간 예산을 초과해 결과가 미확정인 값입니다. 재시도 전 중복 side
     * effect 여부를
     * 확인해야 합니다.
     */
    TOTAL_TIMEOUT,
}
/**
 * VoucherPoolShutdownDeadlines은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property transactionDrain 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property claimRelease 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property phase 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property total 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
 */


internal class VoucherPoolShutdownDeadlines(
    /**
     * `transactionDrain`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val transactionDrain: Duration = DEFAULT_TRANSACTION_DRAIN_SECONDS.seconds(),
    /**
     * `claimRelease`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val claimRelease: Duration = DEFAULT_CLAIM_RELEASE_SECONDS.seconds(),
    /**
     * `phase`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val phase: Duration = DEFAULT_PHASE_SECONDS.seconds(),
    /**
     * `total`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val total: Duration = DEFAULT_TOTAL_SECONDS.seconds(),
) {

    private companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val DEFAULT_TRANSACTION_DRAIN_SECONDS = 12L
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        const val DEFAULT_CLAIM_RELEASE_SECONDS = 5L
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val DEFAULT_PHASE_SECONDS = 5L
        /**
         * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
         */
        const val DEFAULT_TOTAL_SECONDS = 45L
    }

    init {
        transactionDrain.requirePositiveDuration("transactionDrain")
        claimRelease.requirePositiveDuration("claimRelease")
        phase.requirePositiveDuration("phase")
        total.requirePositiveDuration("total")

        require(total >= transactionDrain + claimRelease) {
            "total shutdown deadline must include drain and claim release"
        }
    }
}

@Suppress("TooManyFunctions")
/**
 * VoucherPoolLifecycleActions은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface VoucherPoolLifecycleActions {
    /**
     * readinessDown은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun readinessDown()
    /**
     * rejectCommandsAndSse은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun rejectCommandsAndSse()
    /**
     * stopTriggers은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun stopTriggers()
    /**
     * stopClaims은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun stopClaims()
    /**
     * awaitTransactions은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun awaitTransactions(timeout: Duration): Boolean
    /**
     * cancelTransactions은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun cancelTransactions()
    /**
     * verifyRollback은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun verifyRollback(): Boolean
    /**
     * awaitClaimRelease은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun awaitClaimRelease(timeout: Duration): Boolean
    /**
     * closeSseAndPoller은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun closeSseAndPoller()
    /**
     * closeAdvisoryResources은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게
     * 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun closeAdvisoryResources()
    /**
     * closeExecutor은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun closeExecutor()
    /**
     * closeDataSource은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun closeDataSource()
}

/** infrastructure teardown 전에 application-owned SSE subscription과 공유 poller를 닫습니다. */
/** infrastructure teardown 전에 application-owned SSE subscription과 공유 poller를 닫습니다. */
internal fun interface VoucherPoolStreamShutdown {
    /**
     * closeSseAndPoller은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun closeSseAndPoller()
}

/**
 * 검증용 injected deadline을 가진 deterministic, idempotent shutdown coordinator입니다.
 * @property actions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property deadlines 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 */
internal class VoucherPoolLifecycleCoordinator(
    /**
     * `actions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val actions: VoucherPoolLifecycleActions,
    /**
     * `deadlines`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val deadlines: VoucherPoolShutdownDeadlines = VoucherPoolShutdownDeadlines(),
) {

    companion object: KLogging()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val shuttingDown = AtomicBoolean()
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val eventLock = ReentrantLock()
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private val recordedEvents = ArrayList<VoucherPoolShutdownEvent>()
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    @Volatile
    private var shutdownReason = VoucherPoolShutdownReason.CLEAN

    /**
     * shutdown은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        val totalDeadline = System.nanoTime() + deadlines.total.toNanos()

        phase(VoucherPoolShutdownEvent.READINESS_DOWN, totalDeadline, actions::readinessDown)
        phase(VoucherPoolShutdownEvent.COMMANDS_AND_SSE_REJECTED, totalDeadline, actions::rejectCommandsAndSse)
        phase(VoucherPoolShutdownEvent.TRIGGERS_STOPPED, totalDeadline, actions::stopTriggers)
        phase(VoucherPoolShutdownEvent.CLAIMS_STOPPED, totalDeadline, actions::stopClaims)

        drain(totalDeadline)

        phase(VoucherPoolShutdownEvent.SSE_AND_POLLER_CLOSED, totalDeadline, actions::closeSseAndPoller)
        phase(VoucherPoolShutdownEvent.ADVISORY_RESOURCES_CLOSED, totalDeadline, actions::closeAdvisoryResources)
        phase(VoucherPoolShutdownEvent.EXECUTOR_CLOSED, totalDeadline, actions::closeExecutor)
        phase(VoucherPoolShutdownEvent.DATASOURCE_CLOSED, totalDeadline, actions::closeDataSource)

        log.info { "voucher_pool_shutdown_completed reason=$shutdownReason" }
    }

    /**
     * events은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun events(): List<VoucherPoolShutdownEvent> = eventLock.withLock { recordedEvents.toList() }

    /**
     * reason은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun reason(): VoucherPoolShutdownReason = shutdownReason

    /**
     * drain은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param totalDeadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun drain(totalDeadline: Long) {
        record(VoucherPoolShutdownEvent.TRANSACTION_DRAIN)
        val drainBudget = minOf(deadlines.transactionDrain, remaining(totalDeadline))

        if (drainBudget.isZero || !actions.awaitTransactions(drainBudget)) {
            shutdownReason = VoucherPoolShutdownReason.TRANSACTION_DRAIN_TIMEOUT
            log.warn { "voucher_pool_shutdown_forced reason=$shutdownReason" }

            runBounded(totalDeadline, actions::cancelTransactions)
            runBounded(totalDeadline) { check(actions.verifyRollback()) { "transaction rollback was not verified" } }

            val releaseBudget = minOf(deadlines.claimRelease, remaining(totalDeadline))
            runBounded(totalDeadline) {
                check(!releaseBudget.isZero && actions.awaitClaimRelease(releaseBudget)) {
                    "worker claim release deadline exceeded"
                }
            }
        }
    }

    /**
     * phase은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param totalDeadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun phase(event: VoucherPoolShutdownEvent, totalDeadline: Long, action: () -> Unit) {
        record(event)
        runBounded(totalDeadline, action)
    }

    /**
     * runBounded은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param totalDeadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun runBounded(totalDeadline: Long, action: () -> Unit): Boolean {
        val budget = minOf(deadlines.phase, remaining(totalDeadline))
        if (budget.isZero) {
            updateReason(VoucherPoolShutdownReason.TOTAL_TIMEOUT)
            return false
        }
        val completed = CountDownLatch(1)
        var failure: Throwable? = null
        val thread = Thread.ofVirtual().name("voucher-pool-shutdown-phase").start {
            try {
                action()
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                completed.countDown()
            }
        }

        val finished = completed.await(budget.toKotlinDuration())
        if (!finished) {
            thread.interrupt()
            updateReason(VoucherPoolShutdownReason.PHASE_TIMEOUT)
            return false
        }
        if (failure != null) {
            updateReason(VoucherPoolShutdownReason.PHASE_FAILED)
            log.warn { "voucher_pool_shutdown_phase_failed failure=${failure.javaClass.simpleName}" }
            return false
        }
        return true
    }

    /**
     * record은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun record(event: VoucherPoolShutdownEvent) {
        eventLock.withLock { recordedEvents += event }
    }

    /**
     * updateReason은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun updateReason(reason: VoucherPoolShutdownReason) {
        if (shutdownReason == VoucherPoolShutdownReason.CLEAN) {
            shutdownReason = reason
        }
    }

    /**
     * remaining은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param deadline 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun remaining(deadline: Long): Duration =
        ((deadline - System.nanoTime()).coerceAtLeast(0L)).nanos()

}

/** scheduled worker와 Task 8 claim path가 공유하는 rejection/cancellation state입니다. */
/** scheduled worker와 Task 8 claim path가 공유하는 rejection/cancellation state입니다. */
@Component
internal class VoucherPoolRuntimeControl {

    companion object: KLogging()
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */

    private val acceptingCommands = AtomicBoolean(true)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val acceptingClaims = AtomicBoolean(true)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val triggersRunning = AtomicBoolean(true)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val claimLock = ReentrantLock()
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val claimsReleased = claimLock.newCondition()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private var activeClaims = 0

    /**
     * rejectCommandsAndSse은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun rejectCommandsAndSse() {
        acceptingCommands.set(false)
    }

    /**
     * stopTriggers은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun stopTriggers() {
        triggersRunning.set(false)
    }

    /**
     * stopClaims은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun stopClaims() {
        claimLock.withLock {
            acceptingClaims.set(false)
            if (activeClaims == 0) claimsReleased.signalAll()
        }
    }

    /**
     * commandsAccepted은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun commandsAccepted(): Boolean = acceptingCommands.get()

    /**
     * claimsAccepted은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun claimsAccepted(): Boolean = acceptingClaims.get()

    /**
     * triggersRunning은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun triggersRunning(): Boolean = triggersRunning.get()

    /**
     * withClaim은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> withClaim(block: () -> T): T? {
        val reserved = claimLock.withLock {
            if (!acceptingClaims.get()) {
                false
            } else {
                activeClaims++
                true
            }
        }
        if (!reserved) return null

        return try {
            block()
        } finally {
            claimLock.withLock {
                activeClaims--
                check(activeClaims >= 0) { "worker claim accounting underflow" }
                if (activeClaims == 0) claimsReleased.signalAll()
            }
        }
    }

    /**
     * awaitClaimRelease은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun awaitClaimRelease(timeout: Duration): Boolean {
        timeout.requireZeroOrPositiveDuration("timeout")

        var remaining = timeout.toNanos()
        claimLock.withLock {
            while (activeClaims > 0 && remaining > 0L) {
                remaining = claimsReleased.awaitNanos(remaining)
            }
            return activeClaims == 0
        }
    }

}

/** Spring이 infrastructure bean을 destroy하기 전에 application-owned shutdown을 실행합니다. */
/** Spring이 infrastructure bean을 destroy하기 전에 application-owned shutdown을 실행합니다. */
@Component
@Suppress("LongParameterList")
internal class VoucherPoolLifecycle(
    applicationContext: ApplicationContext,
    gate: DatabasePermitGate,
    runtime: VoucherPoolRuntimeControl,
    health: VoucherPoolHealthState,
    streamShutdown: ObjectProvider<VoucherPoolStreamShutdown>,
    redis: ObjectProvider<VoucherPoolRedisResources>,
    @Qualifier("voucherPoolExecutor") executor: ObjectProvider<ExecutorService>,
    dataSource: DataSource,
): SmartLifecycle {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val running = AtomicBoolean()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    @Suppress("TooManyFunctions")
    private val coordinator = VoucherPoolLifecycleCoordinator(
        actions = object: VoucherPoolLifecycleActions {
            /**
             * readinessDown은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
             *
             * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
             */
            override fun readinessDown() {
                health.fail(VoucherPoolHealthComponent.LIFECYCLE, VoucherPoolHealthReason.SHUTTING_DOWN)
                AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)
            }

            /**
             * rejectCommandsAndSse은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은
             * 멱등하게 유지합니다.
             *
             * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
             */
            override fun rejectCommandsAndSse() {
                runtime.rejectCommandsAndSse()
                gate.beginShutdown()
            }

            /**
             * stopTriggers은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
             *
             * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
             */
            override fun stopTriggers() = runtime.stopTriggers()

            /**
             * stopClaims은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
             * 거절합니다.
             *
             * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
             */
            override fun stopClaims() = runtime.stopClaims()

            /**
             * awaitTransactions은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
             *
             * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
             *
             * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
             */
            override fun awaitTransactions(timeout: Duration): Boolean = gate.awaitDrained(timeout)

            /**
             * cancelTransactions은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게
             * 유지합니다.
             *
             * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
             */
            override fun cancelTransactions() {
                gate.cancelActiveTransactions()
            }

            /**
             * verifyRollback은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
             *
             * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
             */
            override fun verifyRollback(): Boolean =
                gate.awaitDrained(ROLLBACK_VERIFY_TIMEOUT_SECONDS.seconds())

            /**
             * awaitClaimRelease은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자
             * 불일치를 거절합니다.
             *
             * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
             *
             * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
             */
            override fun awaitClaimRelease(timeout: Duration): Boolean = runtime.awaitClaimRelease(timeout)

            /**
             * closeSseAndPoller은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게
             * 유지합니다.
             *
             * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
             */
            override fun closeSseAndPoller() {
                streamShutdown.ifAvailable?.closeSseAndPoller()
            }

            /**
             * closeAdvisoryResources은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은
             * 멱등하게 유지합니다.
             *
             * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
             */
            override fun closeAdvisoryResources() {
                redis.ifAvailable?.close()
            }

            /**
             * closeExecutor은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게
             * 유지합니다.
             *
             * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
             */
            override fun closeExecutor() {
                executor.ifAvailable?.let { service ->
                    service.shutdown()
                    if (!service.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_SECONDS.seconds)) {
                        service.shutdownNow()
                    }
                }
            }

            /**
             * closeDataSource은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게
             * 유지합니다.
             *
             * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
             */
            override fun closeDataSource() {
                when (dataSource) {
                    is HikariDataSource -> dataSource.close()
                    is AutoCloseable    -> dataSource.close()
                }
            }
        },
    )

    /**
     * start은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun start() {
        running.set(true)
    }

    /**
     * stop은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param callback 업무 처리에 필요한 callback 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun stop(callback: Runnable) {
        if (running.compareAndSet(true, false)) {
            coordinator.shutdown()
        }
        callback.run()
    }

    /**
     * stop은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun stop() = stop {}

    /**
     * isRunning은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun isRunning(): Boolean = running.get()

    /**
     * isAutoStartup은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun isAutoStartup(): Boolean = true

    /**
     * getPhase은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun getPhase(): Int = Int.MAX_VALUE - LIFECYCLE_PHASE_OFFSET

    /**
     * events은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    internal fun events(): List<VoucherPoolShutdownEvent> = coordinator.events()

    /**
     * reason은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    internal fun reason(): VoucherPoolShutdownReason = coordinator.reason()

    private companion object: KLogging() {
        /**
         * `EXECUTOR_CLOSE_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val EXECUTOR_CLOSE_TIMEOUT_SECONDS = 5L
        /**
         * `LIFECYCLE_PHASE_OFFSET`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        const val LIFECYCLE_PHASE_OFFSET = 100
        /**
         * `ROLLBACK_VERIFY_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val ROLLBACK_VERIFY_TIMEOUT_SECONDS = 5L
    }
}
