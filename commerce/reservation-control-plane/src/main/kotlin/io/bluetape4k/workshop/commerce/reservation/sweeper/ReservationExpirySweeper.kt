package io.bluetape4k.workshop.commerce.reservation.sweeper

import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.springframework.scheduling.annotation.Scheduled
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * scheduler log와 결정적 sweep assertion에 사용하는 bounded work summary입니다.
 * @property scannedResources 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property expiredHolds 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property promotedEntries 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property staleConflicts 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class SweepBatchSummary(
    /**
     * `scannedResources`은 예약 제어면에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val scannedResources: Int,
    /**
     * `expiredHolds`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expiredHolds: Int,
    /**
     * `promotedEntries`은 예약 제어면에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val promotedEntries: Int,
    /**
     * `staleConflicts`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val staleConflicts: Int,
): Serializable {
    init {
        scannedResources.requireZeroOrPositiveNumber("scannedResources")
        expiredHolds.requireZeroOrPositiveNumber("expiredHolds")
        promotedEntries.requireZeroOrPositiveNumber("promotedEntries")
        staleConflicts.requireZeroOrPositiveNumber("staleConflicts")
    }

    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
        /**
         * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */

        @JvmField
        val Empty = SweepBatchSummary(
            scannedResources = 0,
            expiredHolds = 0,
            promotedEntries = 0,
            staleConflicts = 0
        )
    }
}

/** advisory scheduling gate가 성공한 뒤에만 호출되는 PostgreSQL-owned expiry batch입니다. */
/** advisory scheduling gate가 성공한 뒤에만 호출되는 PostgreSQL-owned expiry batch입니다. */
fun interface ReservationSweepWork {
    /**
     * sweep은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param maxResources 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     * @param budget 업무 처리에 필요한 budget 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun sweep(
        maxResources: Int,
        budget: Duration,
    ): SweepBatchSummary
}

/** leader library의 elected, skipped, failed outcome을 보존하는 adapter boundary입니다. */
/** leader library의 elected, skipped, failed outcome을 보존하는 adapter boundary입니다. */
fun interface SweepLeaderGate {
    /**
     * run은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param slot 업무 처리에 필요한 slot 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun run(
        slot: LeaderSlot,
        action: () -> SweepBatchSummary,
    ): LeaderRunResult<SweepBatchSummary>
}

/**
 * elected-null과 skipped를 합치지 않고 published leader 0.4.0 result API를 호출합니다.
 * @property elector 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class LeaderElectorSweepGate(
    /**
     * `elector`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val elector: LeaderElector,
): SweepLeaderGate {
    companion object: KLogging()

    /**
     * run은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param slot 업무 처리에 필요한 slot 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun run(
        slot: LeaderSlot,
        action: () -> SweepBatchSummary,
    ): LeaderRunResult<SweepBatchSummary> = elector.runIfLeaderResult(slot, action)
}

/** 안정적인 operational failure category입니다. 어떤 category도 PostgreSQL correctness 약화를 의미하지 않습니다. */
/** 안정적인 operational failure category입니다. 어떤 category도 PostgreSQL correctness 약화를 의미하지 않습니다. */
enum class SweepFailureCode {
    /**
     * `SweepFailureCode.LEADER_BACKEND_UNAVAILABLE`은 leader election backend가 없어 해당 노드가 리더가 될 수 없는
     * 값입니다.
     */
    LEADER_BACKEND_UNAVAILABLE,
    /**
     * `SweepFailureCode.ACTION_FAILED`은 운영자·worker action이 실패해 결과를 확정하지 못한 값입니다.
     */
    ACTION_FAILED,
    /**
     * `SweepFailureCode.EMPTY_RESULT`은 처리할 대상이나 결과가 없어 side effect 없이 종료한 값입니다.
     */
    EMPTY_RESULT
}

/** local suppression과 distributed leader skip을 포함하는 scheduler tick outcome 하나입니다. */
/** local suppression과 distributed leader skip을 포함하는 scheduler tick outcome 하나입니다. */
sealed interface SweepTickOutcome: Serializable {
/**
 * Completed은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property summary 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */

    data class Completed(
        /**
         * `summary`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val summary: SweepBatchSummary,
    ): SweepTickOutcome {
        companion object {
            /**
             * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID = 1L
        }
    }

    /**
     * Skipped은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object Skipped: SweepTickOutcome {
        /**
         * readResolve은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        private fun readResolve(): Any = Skipped
    }

    /**
     * LocalBusy은 예약 제어면 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object LocalBusy: SweepTickOutcome {
        /**
         * readResolve은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        private fun readResolve(): Any = LocalBusy
    }
/**
 * Failed은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property code 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Failed(
        /**
         * `code`은 예약 제어면에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val code: SweepFailureCode,
    ): SweepTickOutcome {
        companion object {
            /**
             * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID = 1L
        }
    }
}

/**
 * local single-flight gate와 distributed leader gate가 동의할 때만 bounded expiry batch 하나를 실행합니다.
 * duplicate safety는 계속 PostgreSQL CAS와 unique constraint가 담당합니다.
 * @property leaderGate 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property sweepWork 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property instanceId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 * @property maxResources 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property tickBudget 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class ReservationExpirySweeper(
    /**
     * `leaderGate`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val leaderGate: SweepLeaderGate,
    /**
     * `sweepWork`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val sweepWork: ReservationSweepWork,
    /**
     * `instanceId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    private val instanceId: String,
    /**
     * `maxResources`은 예약 제어면에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    private val maxResources: Int = 32,
    /**
     * `tickBudget`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val tickBudget: Duration = 5.seconds(),
) {
    companion object: KLogging() {
        /**
         * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val LOCK_NAME = "reservation-expiry-sweeper"
    }
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val running = AtomicBoolean(false)
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val slot = LeaderSlot(LOCK_NAME, instanceId)

    init {
        maxResources.requireInRange(1, 32, "maxResources")
        tickBudget.requirePositiveDuration("tickBudget")
    }

    @Scheduled(
        fixedDelayString = "\${reservation.sweeper.fixed-delay:PT5S}",
        initialDelayString = "\${reservation.sweeper.initial-delay:PT5S}"
    )
    /**
     * scheduledSweep은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun scheduledSweep() {
        sweepOnce()
    }

    /**
     * sweepOnce은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun sweepOnce(): SweepTickOutcome {
        if (!running.compareAndSet(false, true)) {
            log.debug { "reservation_sweep_local_busy outcome=SKIPPED" }
            return SweepTickOutcome.LocalBusy
        }

        return try {
            val leaderResult = try {
                leaderGate.run(slot) { sweepWork.sweep(maxResources, tickBudget) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (_: Exception) {
                log.warn { "reservation_sweep_backend_failed reason=LEADER_BACKEND_UNAVAILABLE" }
                return SweepTickOutcome.Failed(SweepFailureCode.LEADER_BACKEND_UNAVAILABLE)
            }

            when (leaderResult) {
                is LeaderRunResult.Elected -> {
                    val summary = leaderResult.value
                    if (summary == null) {
                        log.warn { "reservation_sweep_failed reason=EMPTY_RESULT" }
                        SweepTickOutcome.Failed(SweepFailureCode.EMPTY_RESULT)
                    } else {
                        log.debug {
                            "reservation_sweep_completed scanned=${summary.scannedResources} " +
                                    "expired=${summary.expiredHolds} promoted=${summary.promotedEntries} " +
                                    "stale=${summary.staleConflicts}"
                        }
                        SweepTickOutcome.Completed(summary)
                    }
                }
                LeaderRunResult.Skipped    -> {
                    log.debug { "reservation_sweep_leader_skipped outcome=SKIPPED" }
                    SweepTickOutcome.Skipped
                }
                is LeaderRunResult.ActionFailed -> {
                    log.warn { "reservation_sweep_action_failed reason=ACTION_FAILED" }
                    SweepTickOutcome.Failed(SweepFailureCode.ACTION_FAILED)
                }
            }
        } finally {
            running.set(false)
        }
    }
}
