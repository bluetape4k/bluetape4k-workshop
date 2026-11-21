package io.bluetape4k.workshop.commerce.voucher.eventsourced.snapshot

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRead
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import java.time.Duration
import java.time.Instant

/**
 * `MAX_FOREGROUND_REPLAY_EVENTS`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
 *
 * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
 */
internal const val MAX_FOREGROUND_REPLAY_EVENTS = 10_000
/**
 * `MAX_FOREGROUND_REPLAY_SECONDS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MAX_FOREGROUND_REPLAY_SECONDS = 2L
/**
 * `MAX_FOREGROUND_REPLAY_DURATION`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private val MAX_FOREGROUND_REPLAY_DURATION: Duration = MAX_FOREGROUND_REPLAY_SECONDS.seconds()

/**
 * RehydrationLimitExceeded은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class RehydrationLimitExceeded(message: String) : IllegalStateException(message)

/**
 * RehydrationRequest은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class RehydrationRequest<T : Any>(
    /**
     * `stream`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val stream: StreamKey,
    /**
     * `emptyState`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val emptyState: () -> T,
    /**
     * `restoreSnapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val restoreSnapshot: (String) -> T,
    /**
     * `apply`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val apply: (T, List<EventEnvelope>) -> T,
    /**
     * `keyVersionAvailable`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val keyVersionAvailable: (Int) -> Boolean,
)

/**
 * RehydratedState은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class RehydratedState<T : Any>(
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: T,
    /**
     * `snapshotVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val snapshotVersion: Long,
    /**
     * `replayedEvents`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val replayedEvents: Int,
)

/**
 * snapshot은 optional accelerator입니다. invalid, stale, retired-key snapshot은 event로 fallback합니다.
 * @property eventStore 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property snapshotLoader 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property now 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
 * @property maxEvents 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property maxDuration 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */
internal class BoundedRehydrator(
    /**
     * `eventStore`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventStore: EventStorePort,
    /**
     * `snapshotLoader`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val snapshotLoader: (StreamKey) -> EventSnapshot?,
    /**
     * `now`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val now: () -> Instant = Instant::now,
    /**
     * `maxEvents`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val maxEvents: Int = MAX_FOREGROUND_REPLAY_EVENTS,
    /**
     * `maxDuration`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val maxDuration: Duration = MAX_FOREGROUND_REPLAY_DURATION,
) {
    /**
     * rehydrate은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun <T : Any> rehydrate(request: RehydrationRequest<T>): RehydratedState<T> =
        rehydrateFrom(request, loadSnapshot(request), now())

    /**
     * rehydrateFrom은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param snapshot 업무 처리에 필요한 snapshot 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param startedAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun <T : Any> rehydrateFrom(
        request: RehydrationRequest<T>,
        snapshot: EventSnapshot?,
        startedAt: Instant,
    ): RehydratedState<T> {
        var state = snapshot?.let { request.restoreSnapshot(it.canonicalState) } ?: request.emptyState()
        var afterVersion = snapshot?.metadata?.streamVersion ?: 0L
        val snapshotVersion = afterVersion
        var replayed = 0

        while (true) {
            requireWithinBudget(startedAt, replayed)
            val page = eventStore.load(EventStoreRead(request.stream, afterVersion))
            if (page.committedHead < afterVersion) {
                return rehydrateFrom(request, null, startedAt)
            }
            if (page.events.isEmpty()) {
                return RehydratedState(state, snapshotVersion, replayed)
            }
            replayed += page.events.size
            requireWithinBudget(startedAt, replayed)
            state = request.apply(state, page.events)
            afterVersion = page.events.last().stream.version
        }
    }

    /**
     * loadSnapshot은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun <T : Any> loadSnapshot(request: RehydrationRequest<T>): EventSnapshot? =
        try {
            snapshotLoader(request.stream)?.takeIf { request.keyVersionAvailable(it.metadata.keyVersion) }
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * requireWithinBudget은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param startedAt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun requireWithinBudget(startedAt: Instant, replayed: Int) {
        if (replayed > maxEvents) throw RehydrationLimitExceeded("event replay exceeds $maxEvents events")

        if (Duration.between(startedAt, now()) > maxDuration) {
            throw RehydrationLimitExceeded("event replay exceeds $maxDuration")
        }
    }
}
