package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Instant

/**
 * ProjectionRebuildPollResult은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal sealed interface ProjectionRebuildPollResult {
/**
 * Applied은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property appliedEventCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야
 * 합니다.
 * @property position 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property lag 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */

    data class Applied(
        /**
         * `appliedEventCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        val appliedEventCount: Int,
        /**
         * `position`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val position: Long,
        /**
         * `lag`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val lag: Long,
    ): ProjectionRebuildPollResult

    /**
     * Idle은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object Idle: ProjectionRebuildPollResult

    /**
     * StaleWorker은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    data object StaleWorker: ProjectionRebuildPollResult
/**
 * Degraded은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property reasonClass 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    data class Degraded(val reasonClass: String): ProjectionRebuildPollResult
}

/**
 * bounded page를 non-active generation으로 replay합니다. generation row lock은 candidate read-model
 * mutation과
 * cursor advance 동안 유지되므로, fencing token이나 cancellation revision이 바뀐 뒤 stale batch가 commit된 채 남지
 * 않습니다.
 * @property rebuilds 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property projections 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reader 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property handler 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class ProjectionRebuildWorker(
    /**
     * `rebuilds`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val rebuilds: ProjectionRebuildRepository,
    /**
     * `projections`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val projections: ProjectionRepository,
    /**
     * `reader`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val reader: ProjectionEventReader,
    /**
     * `handler`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val handler: ProjectionEventHandler = AcceptingProjectionEventHandler,
) {

    /**
     * poll은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun poll(
        key: ProjectionKey,
        lease: ProjectionLease,
        now: Instant,
    ): ProjectionRebuildPollResult {
        TransactionManager.current()
        return findGeneration(key)
            ?.let { observed ->
                lockBuildingGeneration(key, observed.fencingToken, observed.cancellationRevision)
                    ?.let { candidate -> pollCandidate(key, lease, candidate, now) }
            }
            ?: ProjectionRebuildPollResult.StaleWorker
    }

    /**
     * pollCandidate은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param candidate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun pollCandidate(
        key: ProjectionKey,
        lease: ProjectionLease,
        candidate: ProjectionGeneration,
        now: Instant,
    ): ProjectionRebuildPollResult {
        TransactionManager.current()
        val events: List<EventEnvelope> =
            reader.loadAfter(candidate.currentPosition).events.takeUntil(candidate.targetPosition)
        if (events.isEmpty()) return ProjectionRebuildPollResult.Idle

        return verifyEvents(key, lease, events, now) ?: applyCandidate(key, lease, candidate, events, now)
    }

    /**
     * verifyEvents은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param events 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun verifyEvents(
        key: ProjectionKey,
        lease: ProjectionLease,
        events: List<EventEnvelope>,
        now: Instant,
    ): ProjectionRebuildPollResult.Degraded? {
        events.forEach { event ->
            try {
                handler.verify(event)
            } catch (exception: ProjectionPoisonException) {
                projections.poison(key, lease, event, exception.reasonClass, now)
                log.warn {
                    "voucher_rebuild_projection_poisoned projection=${key.projection} generation=${key.generation}"
                }
                return ProjectionRebuildPollResult.Degraded(exception.reasonClass)
            }
        }
        return null
    }

    /**
     * applyCandidate은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param lease 업무 처리에 필요한 lease 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param candidate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param events 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun applyCandidate(
        key: ProjectionKey,
        lease: ProjectionLease,
        candidate: ProjectionGeneration,
        events: List<EventEnvelope>,
        now: Instant,
    ): ProjectionRebuildPollResult.Applied {
        val applied = projections.applyBatch(key, lease, events, now)
        val position = events.last().globalPosition

        rebuilds.advance(
            key = key,
            cursor = ProjectionRebuildCursor(
                fencingToken = candidate.fencingToken,
                cancellationRevision = candidate.cancellationRevision,
                expectedPosition = candidate.currentPosition,
                position = position,
            ),
            now = now,
        ).checkBeTrue { "candidate generation changed while its row lock was held" }

        log.debug { "voucher_rebuild_projection_applied projection=${key.projection} generation=${key.generation}" }

        return ProjectionRebuildPollResult.Applied(
            appliedEventCount = applied.appliedEventCount,
            position = position,
            lag = (candidate.targetPosition - position).coerceAtLeast(0),
        )
    }

    companion object: KLogging()
}

/**
 * takeUntil은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param targetPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun List<EventEnvelope>.takeUntil(targetPosition: Long): List<EventEnvelope> =
    takeWhile { event ->
        event.globalPosition <= targetPosition
    }
