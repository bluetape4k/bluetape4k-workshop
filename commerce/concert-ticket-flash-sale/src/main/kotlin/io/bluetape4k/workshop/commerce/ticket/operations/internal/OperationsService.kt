package io.bluetape4k.workshop.commerce.ticket.operations.internal

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.commerce.ticket.operations.api.OperationsCommands
import io.bluetape4k.workshop.commerce.ticket.operations.api.OperatorReconcile
import io.bluetape4k.workshop.commerce.ticket.operations.api.ReconcileSummary
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Semaphore

/**
 * `ReconciliationResult`은 명령 처리 결과입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
enum class ReconciliationResult {
    /**
     * `ReconciliationResult.CONVERGED`은 대상 projection·원장·provider 상태가 일치해 추가 보정이 불필요한 값입니다.
     */
    CONVERGED,
    /**
     * `ReconciliationResult.RETRY`은 일시 오류로 판단되어 동일한 명령과 idempotency key를 재시도할 수 있는 값입니다.
     */
    RETRY,
    /**
     * `ReconciliationResult.QUARANTINED`은 원인을 자동 분류할 수 없어 격리하고 운영자 확인을 기다리는 값입니다.
     */
    QUARANTINED
}

/**
 * `ReconciliationJob`은 운영 작업 콘솔 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
fun interface ReconciliationJob {
    /**
     * run은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun run(): ReconciliationResult?
}

/**
 * 독립적인 concurrency, batch, wall-clock 한계 아래에서 operator recovery를 실행합니다.
 * @property jobs 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property maxBatchSize 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 * @property runDeadline 콘서트 티켓 판매의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 * @property clock 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class OperationsService(
    /**
     * `jobs`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jobs: List<ReconciliationJob>,
    operatorPermits: Int,
    /**
     * `maxBatchSize`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val maxBatchSize: Int,
    /**
     * `runDeadline`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val runDeadline: Duration,
    /**
     * `clock`은 콘서트 티켓 플래시 세일에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock = Clock.systemUTC(),
): OperationsCommands {

    companion object: KLogging()
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val permits = Semaphore(operatorPermits, true)

    /**
     * reconcile은 콘서트 티켓 판매의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 콘서트 티켓 판매 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    override fun reconcile(command: OperatorReconcile): ReconcileSummary {
        command.limit.requireInRange(1, maxBatchSize, "command.limit")
        command.reason.length.requireInRange(8, 200, "command.reason.length")

        check(permits.tryAcquire()) { "operator_reconciliation_busy" }

        try {
            val deadline = clock.instant().plus(runDeadline)
            var processed = 0
            var converged = 0
            var quarantined = 0
            for (job in jobs) {
                if (processed >= command.limit || !clock.instant().isBefore(deadline)) break

                when (job.run() ?: continue) {
                    ReconciliationResult.CONVERGED -> converged++
                    ReconciliationResult.QUARANTINED -> quarantined++
                    ReconciliationResult.RETRY     -> Unit
                }
                processed++
            }
            return ReconcileSummary(processed, converged, quarantined)
        } finally {
            permits.release()
        }
    }

    /**
     * availablePermits은 콘서트 티켓 판매의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun availablePermits(): Int = permits.availablePermits()
}
