package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.toKotlinDuration

/**
 * `MAX_CURSOR_LENGTH`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MAX_CURSOR_LENGTH = 96
/**
 * `CURSOR_COMPONENT_COUNT`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val CURSOR_COMPONENT_COUNT = 3
/**
 * `MAX_SSE_SUBSCRIPTIONS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MAX_SSE_SUBSCRIPTIONS = 256
/**
 * `MAX_SSE_QUEUE_SIZE`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
private const val MAX_SSE_QUEUE_SIZE = 256
/**
 * `DEFAULT_POLL_INTERVAL_MILLIS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_POLL_INTERVAL_MILLIS = 250L
/**
 * `DEFAULT_HEARTBEAT_INTERVAL_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 10L
/**
 * `DEFAULT_WRITE_TIMEOUT_SECONDS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DEFAULT_WRITE_TIMEOUT_SECONDS = 2L
/**
 * EventSourcedStreamCursor은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property streamPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property globalPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property projectionPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class EventSourcedStreamCursor(
    /**
     * `streamPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val streamPosition: Long,
    /**
     * `globalPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val globalPosition: Long,
    /**
     * `projectionPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val projectionPosition: Long,
) {
    init {
        streamPosition.requireZeroOrPositiveNumber("cursor.streamPosition")
        globalPosition.requireZeroOrPositiveNumber("cursor.globalPosition")
        projectionPosition.requireZeroOrPositiveNumber("cursor.projectionPosition")
        streamPosition.requireLe(globalPosition, "cursor.streamPosition")
        projectionPosition.requireLe(globalPosition, "cursor.projectionPosition")
    }

    /**
     * toString은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun toString(): String = "$streamPosition:$globalPosition:$projectionPosition"

    companion object {
        /**
         * parse은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
         * 분류합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param raw 업무 처리에 필요한 raw 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        fun parse(raw: String?): EventSourcedStreamCursor? {
            if (raw == null) return null
            raw.length.requireInRange(CURSOR_COMPONENT_COUNT, MAX_CURSOR_LENGTH, "Last-Event-ID.length")
            val components = raw.split(':')
            components.size.requireEquals(CURSOR_COMPONENT_COUNT, "Last-Event-ID.components")

            return EventSourcedStreamCursor(
                streamPosition = components[0].toLongOrNull().requireNotNull("cursor.streamPosition"),
                globalPosition = components[1].toLongOrNull().requireNotNull("cursor.globalPosition"),
                projectionPosition = components[2].toLongOrNull().requireNotNull("cursor.projectionPosition"),
            )
        }
    }
}

/**
 * public event shape는 event payload, subject, token, digest, fencing data를 의도적으로 제외합니다.
 * @property campaignId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property streamPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property globalPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property projectionPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property lag 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property observedAt 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */
internal data class EventSourcedPublicEventDescriptor(
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `streamPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val streamPosition: Long,
    /**
     * `globalPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val globalPosition: Long,
    /**
     * `projectionPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val projectionPosition: Long,
    /**
     * `lag`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val lag: Long,
    /**
     * `observedAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val observedAt: Instant,
) {
    init {
        state.requireNotBlank("state")
        streamPosition.requireZeroOrPositiveNumber("streamPosition")
        globalPosition.requireZeroOrPositiveNumber("globalPosition")
        projectionPosition.requireZeroOrPositiveNumber("projectionPosition")
        streamPosition.requireLe(globalPosition, "streamPosition")
        projectionPosition.requireLe(globalPosition, "projectionPosition")
        lag.requireEquals(globalPosition - projectionPosition, "lag")
    }

    /**
     * cursor은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun cursor(): EventSourcedStreamCursor =
        EventSourcedStreamCursor(streamPosition, globalPosition, projectionPosition)
}
/**
 * EventSourcedStreamEvent은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property cursor 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property event 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property data 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만 포함합니다.
 * @property terminal 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */


internal data class EventSourcedStreamEvent(
    /**
     * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursor: EventSourcedStreamCursor,
    /**
     * `event`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val event: String,
    /**
     * `data`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val data: String,
    /**
     * `terminal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val terminal: Boolean = false,
) {
    init {
        event.requireNotBlank("event")
        data.requireNotBlank("data")
    }
}
/**
 * EventSourcedSseProperties은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property maxSubscriptions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property queueSize 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property pollInterval 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property heartbeatInterval 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property writeTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 */


@ConfigurationProperties("voucher.sse")
internal data class EventSourcedSseProperties(
    /**
     * `maxSubscriptions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val maxSubscriptions: Int = 32,
    /**
     * `queueSize`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val queueSize: Int = 32,
    /**
     * `pollInterval`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL_MILLIS.millis(),
    /**
     * `heartbeatInterval`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL_SECONDS.seconds(),
    /**
     * `writeTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val writeTimeout: Duration = DEFAULT_WRITE_TIMEOUT_SECONDS.seconds(),
) {
    init {
        maxSubscriptions.requireInRange(1, MAX_SSE_SUBSCRIPTIONS, "voucher.sse.max-subscriptions")
        queueSize.requireInRange(1, MAX_SSE_QUEUE_SIZE, "voucher.sse.queue-size")
        pollInterval.requirePositiveDuration("voucher.sse.poll-interval")
        heartbeatInterval.requirePositiveDuration("voucher.sse.heartbeat-interval")
        writeTimeout.requirePositiveDuration("voucher.sse.write-timeout")
    }
}

/**
 * `EventSourcedEventSource`은 이벤트 기반 프로모션 바우처 캠페인 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface EventSourcedEventSource {
    /**
     * read은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun read(
        tenant: String,
        campaignId: UUID,
    ): EventSourcedPublicEventDescriptor
}
/**
 * ProjectionSnapshotEventSource은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property snapshots 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property clock 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
internal class ProjectionSnapshotEventSource(
    /**
     * `snapshots`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val snapshots: CampaignProjectionSnapshotReader,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
): EventSourcedEventSource {
    /**
     * read은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun read(
        tenant: String,
        campaignId: UUID,
    ): EventSourcedPublicEventDescriptor {
        val snapshot = snapshots.read(TenantId(tenant), campaignId)
        val campaign = snapshot.campaign
            ?: throw EventSourcedStreamRejected(
                stableCode = "CAMPAIGN_NOT_FOUND",
                httpStatus = HttpStatus.NOT_FOUND.value(),
                safeReason = "campaign was not found",
            )

        return EventSourcedPublicEventDescriptor(
            campaignId = campaign.campaignId,
            state = campaign.state.name,
            streamPosition = campaign.streamVersion,
            globalPosition = snapshot.positions.streamPosition,
            projectionPosition = snapshot.positions.projectionPosition,
            lag = snapshot.positions.lag,
            observedAt = clock.instant(),
        )
    }
}

/**
 * admission된 각 connection은 bounded virtual-thread poller 하나와 bounded queue 하나를 소유합니다.
 * snapshot-reader transaction은 queue 또는 network write가 시작되기 전에 끝납니다.
 * @property source 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property mapper 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property properties 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
internal class EventSourcedEventStream(
    /**
     * `source`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val source: EventSourcedEventSource,
    /**
     * `mapper`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: JsonMapper,
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: EventSourcedSseProperties,
): AutoCloseable {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val executor: ExecutorService = VirtualThreads.executorService()
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val admission = Semaphore(properties.maxSubscriptions)
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val closed = AtomicBoolean()

    /**
     * open은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param requestedCursor 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun open(
        tenant: String,
        campaignId: UUID,
        requestedCursor: EventSourcedStreamCursor?,
    ): Subscription {
        requireAvailable()
        acquireAdmission()

        return runCatching {
            source.read(tenant, campaignId)
                .also { initial -> validateReconnect(requestedCursor, initial.cursor()) }
                .let { initial -> Subscription(tenant, campaignId, initial).also(Subscription::start) }
        }.onFailure {
            admission.release()
        }.getOrThrow()
    }

    /**
     * connect은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param subscription 업무 처리에 필요한 subscription 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun connect(subscription: Subscription): SseEmitter {
        val emitter = SseEmitter(0L)
        emitter.onCompletion(subscription::close)
        emitter.onTimeout(subscription::close)
        emitter.onError { subscription.close() }

        executor.submit {
            try {
                var streaming = true
                while (streaming && (subscription.isActive() || subscription.queueDepth() > 0)) {
                    val event = subscription.next(properties.heartbeatInterval)
                    streaming = event != null
                    if (event != null) {
                        sendWithDeadline(emitter, event)
                        streaming = !event.terminal
                    }
                }
                emitter.complete()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                log.info { "event_sourced_sse_disconnected reason=interrupted" }
            } catch (_: TimeoutException) {
                log.warn { "event_sourced_sse_disconnected reason=write_timeout" }
            } catch (failure: ExecutionException) {
                log.info { "event_sourced_sse_disconnected reason=${failure.cause?.javaClass?.simpleName}" }
            } finally {
                subscription.close()
            }
        }
        return emitter
    }

    /**
     * close은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @PreDestroy
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.close()
    }

    /**
     * validateReconnect은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param requested 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param current 업무 처리에 필요한 current 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validateReconnect(
        requested: EventSourcedStreamCursor?,
        current: EventSourcedStreamCursor,
    ) {
        if (requested == null) return
        requested.streamPosition.requireLe(current.streamPosition, "cursor.streamPosition")
        requested.globalPosition.requireLe(current.globalPosition, "cursor.globalPosition")
        requested.projectionPosition.requireLe(current.projectionPosition, "cursor.projectionPosition")
    }

    /**
     * requireAvailable은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun requireAvailable() {
        if (closed.get()) {
            throw EventSourcedStreamRejected(
                stableCode = "SSE_SHUTTING_DOWN",
                httpStatus = HttpStatus.SERVICE_UNAVAILABLE.value(),
                safeReason = "event stream is shutting down",
            )
        }
    }

    /**
     * acquireAdmission은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun acquireAdmission() {
        if (!admission.tryAcquire()) {
            throw EventSourcedStreamRejected(
                stableCode = "SSE_CAPACITY_REJECTED",
                httpStatus = HttpStatus.SERVICE_UNAVAILABLE.value(),
                safeReason = "event stream capacity is unavailable",
            )
        }
    }

    /**
     * event은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param descriptor 업무 처리에 필요한 descriptor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param type 업무 처리에 필요한 type 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param terminal 업무 처리에 필요한 terminal 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun event(
        descriptor: EventSourcedPublicEventDescriptor,
        type: String,
        terminal: Boolean = false,
    ): EventSourcedStreamEvent =
        EventSourcedStreamEvent(
            cursor = descriptor.cursor(),
            event = type,
            data = mapper.writeValueAsString(descriptor),
            terminal = terminal,
        )

    /**
     * heartbeat은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun heartbeat(cursor: EventSourcedStreamCursor): EventSourcedStreamEvent =
        EventSourcedStreamEvent(
            cursor = cursor,
            event = "heartbeat",
            data = mapper.writeValueAsString(mapOf("observedAt" to Instant.now())),
        )

    /**
     * sendWithDeadline은 프로모션 바우처 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송
     * 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param emitter 업무 처리에 필요한 emitter 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param event 프로모션 바우처 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     */
    private fun sendWithDeadline(
        emitter: SseEmitter,
        event: EventSourcedStreamEvent,
    ) {
        val send = executor.submit {
            emitter.send(
                SseEmitter.event()
                    .id(event.cursor.toString())
                    .name(event.event)
                    .data(event.data, MediaType.APPLICATION_JSON),
            )
        }
        try {
            send.get(properties.writeTimeout.toKotlinDuration())
        } catch (timeout: TimeoutException) {
            send.cancel(true)
            throw timeout
        }
    }
    /**
     * Subscription은 프로모션 바우처에서 사용하는 업무 객체입니다.
     * @property tenant 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
     * @property campaignId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
     */


    internal inner class Subscription(
        /**
         * 프로모션 바우처 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
         */
        private val tenant: String,
        /**
         * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        private val campaignId: UUID,
        initial: EventSourcedPublicEventDescriptor,
    ): AutoCloseable {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val queue = ArrayBlockingQueue<EventSourcedStreamEvent>(properties.queueSize)
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val cursor = AtomicReference(initial.cursor())
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val active = AtomicBoolean(true)
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val poller = AtomicReference<Future<*>?>()

        init {
            queue.offer(event(initial, "snapshot"))
        }

        /**
         * start은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        fun start() {
            poller.set(executor.submit(::poll))
        }

        /**
         * next은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
         */
        fun next(timeout: Duration): EventSourcedStreamEvent? {
            if (!active.get() && queue.isEmpty()) return null
            return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS) ?: heartbeat(cursor.get())
        }

        /**
         * isActive은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        fun isActive(): Boolean = active.get()

        /**
         * queueDepth은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
         * 매핑합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        fun queueDepth(): Int = queue.size

        /**
         * close은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        override fun close() {
            finish(clearQueue = true)
        }

        /**
         * poll은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        private fun poll() {
            while (active.get()) {
                if (!pause()) return
                try {
                    val descriptor = source.read(tenant, campaignId)
                    val nextCursor = descriptor.cursor()
                    if (nextCursor == cursor.get()) continue
                    cursor.set(nextCursor)
                    if (!queue.offer(event(descriptor, "projection"))) overflow(descriptor)
                } catch (failure: EventSourcedStreamRejected) {
                    terminate(failure.stableCode, failure.safeReason)
                } catch (failure: IllegalArgumentException) {
                    if (active.get()) {
                        log.warn { "event_sourced_sse_poll_failed campaignId=$campaignId failure=${failure.javaClass.simpleName}" }
                    }
                }
            }
        }

        /**
         * pause은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
         */
        private fun pause(): Boolean =
            try {
                TimeUnit.NANOSECONDS.sleep(properties.pollInterval.toNanos())
                true
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

        /**
         * overflow은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param descriptor 업무 처리에 필요한 descriptor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        private fun overflow(descriptor: EventSourcedPublicEventDescriptor) {
            queue.clear()
            queue.offer(event(descriptor, "reset", terminal = true))
            finish(clearQueue = false)
        }

        /**
         * terminate은 프로모션 바우처의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        private fun terminate(
            code: String,
            reason: String,
        ) {
            queue.clear()
            queue.offer(
                EventSourcedStreamEvent(
                    cursor = cursor.get(),
                    event = "error",
                    data = mapper.writeValueAsString(mapOf("code" to code, "reason" to reason)),
                    terminal = true,
                ),
            )
            finish(clearQueue = false)
        }

        /**
         * finish은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
         *
         * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         *
         * @param clearQueue 업무 처리에 필요한 clearQueue 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
         */
        private fun finish(clearQueue: Boolean) {
            if (!active.compareAndSet(true, false)) {
                if (clearQueue) queue.clear()
                return
            }
            poller.getAndSet(null)?.cancel(true)
            if (clearQueue) queue.clear()
            admission.release()
        }
    }

    private companion object: KLogging()
}
/**
 * EventSourcedStreamRejected은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property stableCode 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property httpStatus 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property safeReason 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 */


internal class EventSourcedStreamRejected(
    /**
     * `stableCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val stableCode: String,
    /**
     * `httpStatus`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val httpStatus: Int,
    /**
     * `safeReason`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val safeReason: String,
): RuntimeException(stableCode)
/**
 * EventSourcedEventStreamController은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property streams 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@RestController
internal class EventSourcedEventStreamController(
    /**
     * `streams`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val streams: EventSourcedEventStream,
) {
    /**
     * `campaignEvents`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = campaignEvents(campaignId = UUID.randomUUID(), tenantHeader = "tenant-a",
     * principalHeader = null, lastEventId = "demo")
     * ```
     * @param campaignId 요청 경계에서 검증된 `campaignId`입니다.
     * @param tenantHeader 요청 경계에서 검증된 `tenantHeader`입니다.
     * @param principalHeader 요청 경계에서 검증된 `principalHeader`입니다.
     * @param lastEventId 요청 경계에서 검증된 `lastEventId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/api/v1/campaigns/{campaignId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun campaignEvents(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
    ): ResponseEntity<SseEmitter> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = tenantHeader.requireNotBlank(TENANT_HEADER)
        principalHeader.requireNotBlank(PRINCIPAL_HEADER)
        /**
         * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val cursor = parseEventSourcedCursor(lastEventId)
        /**
         * `subscription`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val subscription = openEventSourcedSubscription(streams, tenant, campaignId, cursor)

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(streams.connect(subscription))
    }
}

/**
 * parseEventSourcedCursor은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무
 * 오류로 분류합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param lastEventId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
private fun parseEventSourcedCursor(lastEventId: String?): EventSourcedStreamCursor? =
    runCatching {
        EventSourcedStreamCursor.parse(lastEventId)
    }.getOrElse {
        throw EventSourcedStreamRejected(
            stableCode = "INVALID_EVENT_CURSOR",
            httpStatus = HttpStatus.BAD_REQUEST.value(),
            safeReason = "event cursor is invalid",
        )
    }

/**
 * openEventSourcedSubscription은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
 * 가리키도록 처리합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param streams 업무 처리에 필요한 streams 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param tenant 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
 * @param campaignId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param cursor 업무 처리에 필요한 cursor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun openEventSourcedSubscription(
    streams: EventSourcedEventStream,
    tenant: String,
    campaignId: UUID,
    cursor: EventSourcedStreamCursor?,
): EventSourcedEventStream.Subscription =
    runCatching {
        streams.open(tenant, campaignId, cursor)
    }.getOrElse { failure ->
        if (failure is IllegalArgumentException) {
            throw EventSourcedStreamRejected(
                stableCode = "INVALID_EVENT_CURSOR",
                httpStatus = HttpStatus.BAD_REQUEST.value(),
                safeReason = "event cursor is invalid",
            )
        }
        throw failure
    }
