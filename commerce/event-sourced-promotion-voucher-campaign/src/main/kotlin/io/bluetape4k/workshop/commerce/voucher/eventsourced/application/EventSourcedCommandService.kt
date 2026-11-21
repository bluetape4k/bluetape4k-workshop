package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.EventSourcedIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptAcquireResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptScope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalDescriptor
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalReplay
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabaseLane
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedPermitTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Duration
import java.time.Instant
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/** 두 transaction 경계를 소유합니다. 먼저 committed receipt를 acquire하고, 이후 atomic append와 terminal finalize를 수행합니다. */
/** 두 transaction 경계를 소유합니다. 먼저 committed receipt를 acquire하고, 이후 atomic append와 terminal finalize를 수행합니다. */
internal interface CommandTransactionRunner {
    /**
     * inTransaction은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> inTransaction(block: () -> T): T
}

/**
 * ExposedCommandTransactionRunner은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class ExposedCommandTransactionRunner(
    database: Database,
    permits: EventSourcedDatabasePermitGate,
): CommandTransactionRunner {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val transactions = EventSourcedPermitTransactionRunner(
        database,
        permits,
        EventSourcedDatabaseLane.FOREGROUND
    )

    /**
     * inTransaction은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param block 업무 처리에 필요한 block 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun <T> inTransaction(block: () -> T): T = transactions.inTransaction(block)
}
/**
 * EventSourcedCommand은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property scope 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property fingerprint 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 * @property acquiredAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 * @property decideAfterRehydrate 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
 */


internal class EventSourcedCommand(
    /**
     * `scope`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val scope: ReceiptScope,
    /**
     * `fingerprint`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fingerprint: ReceiptDigest,
    /**
     * `acquiredAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val acquiredAt: Instant,
    /**
     * `decideAfterRehydrate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val decideAfterRehydrate: () -> EventSourcedCommandDecision,
)
/**
 * EventSourcedCommandDecision은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property appends 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property descriptor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class EventSourcedCommandDecision(
    /**
     * `appends`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val appends: List<ExpectedAppend>,
    /**
     * `descriptor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val descriptor: TerminalDescriptor,
)

/**
 * CommandExecutionResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface CommandExecutionResult {
    /**
     * Executed은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property append 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     * @property descriptor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     */

    class Executed(
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val append: AppendResult.Appended?,
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val descriptor: TerminalDescriptor,
    ): CommandExecutionResult
    /**
     * Replayed은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property descriptor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     */


    class Replayed(val descriptor: TerminalDescriptor): CommandExecutionResult

    /**
     * InProgress은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object InProgress: CommandExecutionResult

    /**
     * FingerprintConflict은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
     * 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object FingerprintConflict: CommandExecutionResult

    /**
     * KeyUnavailable은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object KeyUnavailable: CommandExecutionResult
}
/**
 * EventSourcedCommandService은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property transactions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property receipts 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property eventStore 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property keyVersionAvailable 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property metrics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class EventSourcedCommandService(
    /**
     * `transactions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions: CommandTransactionRunner,
    /**
     * `receipts`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val receipts: EventSourcedIdempotencyRepository,
    /**
     * `eventStore`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventStore: EventStorePort,
    /**
     * `keyVersionAvailable`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val keyVersionAvailable: (Int) -> Boolean = { true },
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: EventSourcedCommandMetrics = EventSourcedCommandMetrics.NONE,
) {
    /**
     * `execute`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = execute(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun execute(command: EventSourcedCommand): CommandExecutionResult {
        val started = TimeSource.Monotonic.markNow()
        val acquired =
            transactions.inTransaction {
                receipts.acquire(command.scope, command.fingerprint, command.acquiredAt)
            }
        val result = when (acquired) {
            is ReceiptAcquireResult.Owner            -> completeOwner(command, acquired)
            is ReceiptAcquireResult.Replay           ->
                when (val replay = acquired.descriptor.replayWith(keyVersionAvailable)) {
                    is TerminalReplay.Replay      -> CommandExecutionResult.Replayed(replay.descriptor)
                    TerminalReplay.KeyUnavailable -> CommandExecutionResult.KeyUnavailable
                }
            is ReceiptAcquireResult.InProgress       -> CommandExecutionResult.InProgress
            ReceiptAcquireResult.FingerprintConflict -> CommandExecutionResult.FingerprintConflict
        }
        val duration = started.elapsedNow().toJavaDuration()
        metrics.commandTerminal(result.status(), duration)

        if (result is CommandExecutionResult.Executed && result.append != null) {
            val eventCount = result.append.lastGlobalPosition - result.append.firstGlobalPosition + 1
            metrics.appendCommitted(eventCount.toInt(), duration)
        }
        return result
    }

    /**
     * completeOwner은 프로모션 바우처의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    private fun completeOwner(
        command: EventSourcedCommand,
        owner: ReceiptAcquireResult.Owner,
    ): CommandExecutionResult {
        return transactions.inTransaction {
            check(receipts.isOwner(command.scope, command.fingerprint, owner.token, command.acquiredAt)) {
                "receipt ownership was lost before append"
            }
            val decision = command.decideAfterRehydrate()
            val append = if (decision.appends.isEmpty()) {
                null
            } else {
                eventStore.appendAll(decision.appends)
            }
            val descriptor = when (append) {
                is AppendResult.Appended ->
                    decision.descriptor.withStreamPosition(append.lastGlobalPosition)
                is AppendResult.Conflict,
                is AppendResult.DuplicateEvent,
                                         -> concurrentModificationDescriptor()
                null                     -> decision.descriptor
            }
            finalize(command, owner, descriptor)
            log.debug { "voucher_command_executed operation=${command.scope.operation}" }

            CommandExecutionResult.Executed(append as? AppendResult.Appended, descriptor)
        }
    }

    /**
     * finalize은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param descriptor 업무 처리에 필요한 descriptor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun finalize(
        command: EventSourcedCommand,
        owner: ReceiptAcquireResult.Owner,
        descriptor: TerminalDescriptor,
    ) {
        receipts.finalize(command.scope, command.fingerprint, owner.token, command.acquiredAt, descriptor)
            .checkBeTrue { "receipt ownership was lost before terminal finalize" }
    }

    /**
     * concurrentModificationDescriptor은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록
     * 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun concurrentModificationDescriptor(): TerminalDescriptor =
        TerminalDescriptor(
            outcome = ReceiptOutcome.CONCURRENT_MODIFICATION,
            status = CONFLICT_STATUS,
        )

    companion object: KLogging() {
        /**
         * `CONFLICT_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        private const val CONFLICT_STATUS = 409
    }
}

/**
 * EventSourcedCommandMetrics은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface EventSourcedCommandMetrics {
    /**
     * `commandTerminal`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = commandTerminal(status = 25, duration = duration)
     * ```
     * @param status 요청 경계에서 검증된 `status`입니다.
     * @param duration 요청 경계에서 검증된 `duration`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun commandTerminal(
        status: Int,
        duration: Duration,
    )

    /**
     * `appendCommitted`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = appendCommitted(eventCount = 25, duration = duration)
     * ```
     * @param eventCount 요청 경계에서 검증된 `eventCount`입니다.
     * @param duration 요청 경계에서 검증된 `duration`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun appendCommitted(
        eventCount: Int,
        duration: Duration,
    )

    companion object {
        val NONE: EventSourcedCommandMetrics = object: EventSourcedCommandMetrics {
            /**
             * `commandTerminal`은 애플리케이션 공개 계약의 업무 진입점입니다.
             *
             * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만
             * 수행합니다. 반환
             * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
             * 예:
             * ```kotlin
             * val response = commandTerminal(status = 25, duration = duration)
             * ```
             * @param status 요청 경계에서 검증된 `status`입니다.
             * @param duration 요청 경계에서 검증된 `duration`입니다.
             * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
             */
            override fun commandTerminal(
                status: Int,
                duration: Duration,
            ) = Unit

            /**
             * `appendCommitted`은 애플리케이션 공개 계약의 업무 진입점입니다.
             *
             * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만
             * 수행합니다. 반환
             * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
             * 예:
             * ```kotlin
             * val response = appendCommitted(eventCount = 25, duration = duration)
             * ```
             * @param eventCount 요청 경계에서 검증된 `eventCount`입니다.
             * @param duration 요청 경계에서 검증된 `duration`입니다.
             * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
             */
            override fun appendCommitted(
                eventCount: Int,
                duration: Duration,
            ) = Unit
        }
    }
}

/**
 * status은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun CommandExecutionResult.status(): Int =
    when (this) {
        is CommandExecutionResult.Executed       -> descriptor.status
        is CommandExecutionResult.Replayed       -> descriptor.status
        is CommandExecutionResult.InProgress     -> CONFLICT_STATUS
        is CommandExecutionResult.FingerprintConflict -> CONFLICT_STATUS
        is CommandExecutionResult.KeyUnavailable -> UNAVAILABLE_STATUS
    }

/**
 * `CONFLICT_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val CONFLICT_STATUS = 409
/**
 * `UNAVAILABLE_STATUS`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
 *
 * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
 */
private const val UNAVAILABLE_STATUS = 503
