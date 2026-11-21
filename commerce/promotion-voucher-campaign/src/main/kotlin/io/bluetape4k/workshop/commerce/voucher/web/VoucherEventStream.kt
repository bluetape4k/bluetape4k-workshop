package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.concurrent.get
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.application.toSnapshot
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMetrics
import io.bluetape4k.workshop.commerce.voucher.config.VoucherProperties
import io.bluetape4k.workshop.commerce.voucher.config.VoucherSseProperties
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import jakarta.annotation.PreDestroy
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.toKotlinDuration

/**
 * EventCursor은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class EventCursor(
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `id`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
) {
    init {
        require(revision >= 0 && id >= 0) { "event cursor values must not be negative" }
    }

    /**
     * toString은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param revision 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun toString(): String = "$revision:$id"

    companion object: KLogging() {
        /**
         * parse은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param raw 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun parse(raw: String?): EventCursor? {
            if (raw == null) return null
            if (raw.length !in 3..MAX_CURSOR_LENGTH || raw.any { it != ':' && !it.isDigit() }) {
                throw invalidCursor()
            }
            val values = raw.split(':')
            if (values.size != 2) throw invalidCursor()
            val revision = values[0].toLongOrNull() ?: throw invalidCursor()
            val id = values[1].toLongOrNull() ?: throw invalidCursor()
            return EventCursor(revision, id)
        }

        /**
         * `MAX_CURSOR_LENGTH`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val MAX_CURSOR_LENGTH = 64
    }
}

/**
 * VoucherStreamEvent은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherStreamEvent(
    /**
     * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursor: EventCursor,
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
)

/**
 * VoucherAuditHttpEvent은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherAuditHttpEvent(
    /**
     * `aggregateType`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val aggregateType: String,
    /**
     * `aggregateId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val aggregateId: UUID,
    /**
     * `revision`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `reasonCode`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reasonCode: String,
    /**
     * `policyVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val policyVersion: Long,
    /**
     * `occurredAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val occurredAt: Instant?,
)

/**
 * VoucherStreamInitial은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherStreamInitial(
    /**
     * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val snapshot: CampaignHttpResponse,
    /**
     * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val cursor: EventCursor,
    /**
     * `resetRequired`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val resetRequired: Boolean,
)

/**
 * VoucherStreamBatch은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherStreamBatch(
    /**
     * `snapshot`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val snapshot: CampaignHttpResponse,
    /**
     * `events`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val events: List<Pair<EventCursor, VoucherAuditHttpEvent>>,
)

/** database work를 subscriber queue와 network write 바깥에 두는 source boundary입니다. */
/** database work를 subscriber queue와 network write 바깥에 두는 source boundary입니다. */
internal interface VoucherEventSource {
    /**
     * initial은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun initial(
        tenantId: String,
        campaignId: UUID,
        requestedCursor: EventCursor?,
    ): VoucherStreamInitial

    /**
     * poll은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun poll(
        tenantId: String,
        campaignId: UUID,
        afterId: Long,
    ): VoucherStreamBatch
}

/** 예약된 SSE-maintenance DB permit만 보유한 채 snapshot과 audit page를 읽습니다. */
/** 예약된 SSE-maintenance DB permit만 보유한 채 snapshot과 audit page를 읽습니다. */
@Component
internal class PostgresVoucherEventSource(
    /**
     * `jdbc`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc: VoucherJdbcExecutor,
    /**
     * `campaigns`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val campaigns: CampaignRepository,
    /**
     * `audits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val audits: AuditRepository,
    /**
     * `mapper`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: ObjectMapper,
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: VoucherProperties,
): VoucherEventSource {
    /**
     * initial은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun initial(
        tenantId: String,
        campaignId: UUID,
        requestedCursor: EventCursor?,
    ): VoucherStreamInitial =
        jdbc.sseMaintenanceTransaction {
            val campaign = campaigns.findPublic(tenantId, campaignId)?.toSnapshot()
                ?: throw VoucherApiException("CAMPAIGN_NOT_FOUND", 404, "campaign was not found")
            val first = audits.firstCampaignAudit(tenantId, campaignId)
            val last = audits.lastCampaignAudit(tenantId, campaignId)
            val reset = validateCursor(tenantId, campaignId, campaign.revision, requestedCursor, first, last)
            VoucherStreamInitial(campaign.toHttp(), last?.toCursor() ?: EventCursor(campaign.revision, 0), reset)
        }

    /**
     * poll은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun poll(
        tenantId: String,
        campaignId: UUID,
        afterId: Long,
    ): VoucherStreamBatch =
        jdbc.sseMaintenanceTransaction {
            val campaign = campaigns.findPublic(tenantId, campaignId)?.toSnapshot()
                ?: throw VoucherApiException("CAMPAIGN_NOT_FOUND", 404, "campaign was not found")
            val bounded = ArrayList<Pair<EventCursor, VoucherAuditHttpEvent>>()
            var encodedBytes = 0
            for (audit in audits.findCampaignAfter(tenantId, campaignId, afterId, properties.sse.maxRows)) {
                val event = audit.toHttpEvent()
                val encodedEventBytes =
                    mapper.writeValueAsBytes(event).size + audit.toCursor().toString().length + SSE_FRAME_BYTES
                if (encodedBytes + encodedEventBytes > properties.sse.maxPayloadBytes) break
                bounded += audit.toCursor() to event
                encodedBytes += encodedEventBytes
            }
            VoucherStreamBatch(campaign.toHttp(), bounded)
        }

    /**
     * validateCursor은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun validateCursor(
        tenantId: String,
        campaignId: UUID,
        campaignRevision: Long,
        requested: EventCursor?,
        first: AuditRecord?,
        last: AuditRecord?,
    ): Boolean {
        if (requested == null) return false
        if (requested.revision > campaignRevision) throw invalidCursor()
        val referenced = requested.id.takeIf { it > 0 }?.let(audits::findCursor)
        if (referenced != null) {
            if (referenced.tenantId != tenantId || referenced.campaignId != campaignId) throw invalidCursor()
            if (referenced.revision != requested.revision) throw invalidCursor()
        }
        if (last == null) {
            if (requested.id > 0) throw invalidCursor()
            return false
        }
        if (requested.id > last.id) throw invalidCursor()
        val beforeRetentionWindow = first != null && requested.id < first.id
        if (requested.id > 0 && referenced == null && !beforeRetentionWindow) throw invalidCursor()
        return beforeRetentionWindow
    }

    private companion object {
        /**
         * `SSE_FRAME_BYTES`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private const val SSE_FRAME_BYTES = 32
    }
}

/**
 * tenant/campaign마다 bounded virtual-thread poller 하나를, subscriber마다 bounded queue 하나를 공유합니다.
 * queue나 network write가 block될 수 있기 전에 database permit을 반환합니다.
 */
@Component
internal class VoucherEventStream(
    /**
     * `source`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val source: VoucherEventSource,
    /**
     * `mapper`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: ObjectMapper,
    /**
     * `executor`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: ExecutorService,
    properties: VoucherProperties,
    /**
     * `metrics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: VoucherMetrics? = null,
): AutoCloseable {
    /**
     * `config`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val config: VoucherSseProperties = properties.sse
    /**
     * `registryLock`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val registryLock = ReentrantLock()
    /**
     * `pollers`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val pollers = HashMap<CampaignStreamKey, CampaignPoller>()
    /**
     * `closed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val closed = AtomicBoolean()

    /**
     * open은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun open(
        tenantId: String,
        campaignId: UUID,
        requestedCursor: EventCursor?,
    ): StreamSubscription {
        if (closed.get()) throw VoucherServiceShuttingDown()
        val key = CampaignStreamKey(tenantId, campaignId)
        registryLock.withLock {
            if (key !in pollers && pollers.size >= config.maxCampaigns) rejectCapacity(campaignId)
        }
        val initial = source.initial(tenantId, campaignId, requestedCursor)
        val subscription = StreamSubscription(config.queueSize, initial.cursor, this)
        subscription.offer(event(initial.cursor, "snapshot", initial.snapshot))
        if (initial.resetRequired) {
            subscription.offer(event(initial.cursor, "reset", initial.snapshot))
        }

        registryLock.withLock {
            val poller =
                pollers[key] ?: run {
                    if (pollers.size >= config.maxCampaigns) rejectCapacity(campaignId)
                    CampaignPoller(key, initial.cursor).also {
                        pollers[key] = it
                        it.start()
                    }
                }
            poller.add(subscription)
        }
        metrics?.sseOpened()
        log.info { "voucher_sse_subscribed campaignId=$campaignId activePollers=${activePollers()}" }
        return subscription
    }

    /**
     * write은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun write(
        subscription: StreamSubscription,
        output: OutputStream,
    ) {
        try {
            while (!subscription.isClosed()) {
                val next = subscription.next(config.heartbeatInterval) ?: break
                writeWithDeadline(output, next)
                if (next.terminal) break
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            log.info { "voucher_sse_disconnected reason=interrupted" }
        } catch (_: TimeoutException) {
            log.warn { "voucher_sse_disconnected reason=write_timeout" }
        } catch (failure: ExecutionException) {
            log.info {
                "voucher_sse_disconnected reason=${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName}"
            }
        } catch (failure: IOException) {
            log.info { "voucher_sse_disconnected reason=${failure.javaClass.simpleName}" }
        } finally {
            subscription.close()
        }
    }

    /**
     * activePollers은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    internal fun activePollers(): Int = registryLock.withLock { pollers.size }

    /**
     * pollDelay은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    internal fun pollDelay(
        tenantId: String,
        campaignId: UUID,
    ): Duration? = registryLock.withLock { pollers[CampaignStreamKey(tenantId, campaignId)]?.currentDelay() }

    @PreDestroy
    /**
     * close은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = registryLock.withLock { pollers.values.toList().also { pollers.clear() } }
        active.forEach(CampaignPoller::stop)
        log.info { "voucher_sse_closed pollers=${active.size}" }
    }

    /**
     * remove은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param subscription 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun remove(subscription: StreamSubscription) {
        val poller = subscription.poller.getAndSet(null) ?: return
        poller.remove(subscription)
    }

    /**
     * rejectCapacity은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param campaignId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun rejectCapacity(campaignId: UUID): Nothing {
        metrics?.sseRejected("CAPACITY")
        throw SseCapacityRejected(campaignId)
    }

    /**
     * removePoller은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param poller 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun removePoller(poller: CampaignPoller) {
        registryLock.withLock { pollers.remove(poller.key, poller) }
    }

    /**
     * event은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun event(
        cursor: EventCursor,
        type: String,
        payload: Any,
        terminal: Boolean = false,
    ): VoucherStreamEvent = VoucherStreamEvent(cursor, type, mapper.writeValueAsString(payload), terminal)

    /**
     * writeWithDeadline은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private fun writeWithDeadline(
        output: OutputStream,
        event: VoucherStreamEvent,
    ) {
        val bytes = event.encode()
        val write = executor.submit { output.write(bytes); output.flush() }
        try {
            write.get(config.writeTimeout.toKotlinDuration())
        } catch (timeout: TimeoutException) {
            write.cancel(true)
            throw timeout
        }
    }

    /**
     * StreamSubscription은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    internal inner class StreamSubscription(
        queueSize: Int,
        initialCursor: EventCursor,
        /**
         * `owner`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        private val owner: VoucherEventStream,
    ): AutoCloseable {
        /**
         * `queue`은 바우처·프로모션에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
         *
         * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
         */
        private val queue = ArrayBlockingQueue<VoucherStreamEvent>(queueSize)
        /**
         * `closed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val closed = AtomicBoolean()
        /**
         * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val cursor = AtomicReference(initialCursor)
        /**
         * `cleanupCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private val cleanupCount = AtomicLong()
        /**
         * `poller`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        internal val poller = AtomicReference<CampaignPoller?>()

        /**
         * offer은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param event 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        internal fun offer(event: VoucherStreamEvent): Boolean {
            if (closed.get()) return false
            if (event.event == "audit" && event.cursor.id <= cursor.get().id) return true
            cursor.set(event.cursor)
            return queue.offer(event)
        }

        /**
         * overflow은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param snapshot 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        internal fun overflow(snapshot: CampaignHttpResponse) {
            if (closed.get()) return
            queue.clear()
            queue.offer(owner.event(cursor.get(), "reset", snapshot, terminal = true))
        }

        /**
         * terminate은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        internal fun terminate(
            type: String,
            payload: Any,
        ) {
            if (closed.get()) return
            queue.clear()
            queue.offer(owner.event(cursor.get(), type, payload, terminal = true))
        }

        /**
         * next은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param timeout 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        internal fun next(timeout: Duration): VoucherStreamEvent? {
            if (closed.get() && queue.isEmpty()) return null
            return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
                ?: owner.event(cursor.get(), "heartbeat", mapOf("observedAt" to Instant.now()))
        }

        /**
         * attach은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param poller 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        internal fun attach(poller: CampaignPoller) {
            check(this.poller.compareAndSet(null, poller)) { "subscription already has a poller" }
        }

        /**
         * isClosed은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        internal fun isClosed(): Boolean = closed.get()

        /**
         * queueDepth은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        internal fun queueDepth(): Int = queue.size

        /**
         * cleanupInvocationCount은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        internal fun cleanupInvocationCount(): Long = cleanupCount.get()

        /**
         * close은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            cleanupCount.incrementAndGet()
            queue.clear()
            owner.remove(this)
            metrics?.sseClosed()
        }
    }

    /**
     * CampaignPoller은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    internal inner class CampaignPoller(
        /**
         * `key`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
         *
         * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
         */
        val key: CampaignStreamKey,
        initialCursor: EventCursor,
    ) {
        /**
         * `subscribers`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val subscribers = ConcurrentHashMap.newKeySet<StreamSubscription>()
        /**
         * `cursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val cursor = AtomicReference(initialCursor)
        /**
         * `running`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val running = AtomicBoolean(true)
        /**
         * `task`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val task = AtomicReference<Future<*>?>()
        /**
         * `delay`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val delay = AtomicReference(config.pollInterval)

        /**
         * start은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        fun start() {
            task.set(executor.submit(::run))
        }

        /**
         * add은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param subscription 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun add(subscription: StreamSubscription) {
            subscription.attach(this)
            subscribers += subscription
        }

        /**
         * remove은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param subscription 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun remove(subscription: StreamSubscription) {
            subscribers -= subscription
            if (subscribers.isEmpty()) {
                stop()
                removePoller(this)
            }
        }

        /**
         * currentDelay은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        fun currentDelay(): Duration = delay.get()

        /**
         * stop은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        fun stop() {
            if (!running.compareAndSet(true, false)) return
            task.getAndSet(null)?.cancel(true)
            subscribers.toList().forEach(StreamSubscription::close)
            subscribers.clear()
        }

        /**
         * run은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        private fun run() {
            while (running.get()) {
                if (!sleep(delay.get())) break
                try {
                    val batch = source.poll(key.tenantId, key.campaignId, cursor.get().id)
                    if (batch.events.isEmpty()) {
                        delay.updateAndGet { it.multipliedBy(2).coerceAtMost(config.maxIdleInterval) }
                        continue
                    }
                    delay.set(config.pollInterval)
                    batch.events.forEach { (nextCursor, audit) ->
                        cursor.set(nextCursor)
                        val next = event(nextCursor, "audit", audit)
                        subscribers.toList().forEach { subscriber ->
                            if (!subscriber.offer(next)) subscriber.overflow(batch.snapshot)
                        }
                    }
                } catch (failure: VoucherApiException) {
                    subscribers.toList().forEach {
                        it.terminate("error", mapOf("code" to failure.stableCode, "reason" to failure.safeReason))
                    }
                    stop()
                } catch (failure: Exception) {
                    if (running.get()) {
                        log.warn { "voucher_sse_poll_failed campaignId=${key.campaignId} failure=${failure.javaClass.simpleName}" }
                    }
                    delay.set(config.maxIdleInterval)
                }
            }
        }

        /**
         * sleep은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param duration 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        private fun sleep(duration: Duration): Boolean =
            try {
                TimeUnit.NANOSECONDS.sleep(duration.toNanos())
                true
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
    }

    companion object: KLogging()
}

/**
 * CampaignStreamKey은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class CampaignStreamKey(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
)

/**
 * SseCapacityRejected은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class SseCapacityRejected(
    /**
     * `campaignId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val campaignId: UUID,
): RuntimeException("SSE_CAPACITY_REJECTED")

/**
 * VoucherServiceShuttingDown은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class VoucherServiceShuttingDown: RuntimeException("SERVICE_SHUTTING_DOWN")

/** same-origin browser fetch stream이 사용하는 header-authenticated SSE endpoint입니다. */
/** same-origin browser fetch stream이 사용하는 header-authenticated SSE endpoint입니다. */
@RestController
internal class VoucherEventStreamController(
    /**
     * `streams`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val streams: VoucherEventStream,
) {
    @GetMapping("/api/v1/campaigns/{campaignId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    /**
     * campaignEvents은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * 예: `GET /v1/campaigns/{campaignId}/events`는 Last-Event-ID 이후의 변경만 SSE로 전달하고, 현재 상태는 REST 조회로 재확인합니다.
     */
    fun campaignEvents(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
    ): ResponseEntity<StreamingResponseBody> {
        /**
         * `tenant`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        /**
         * `subscription`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val subscription = streams.open(tenant, campaignId, EventCursor.parse(lastEventId))
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(StreamingResponseBody { output -> streams.write(subscription, output) })
    }
}

/**
 * toCursor은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun AuditRecord.toCursor(): EventCursor = EventCursor(revision, id)

/**
 * toHttpEvent은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun AuditRecord.toHttpEvent(): VoucherAuditHttpEvent =
    VoucherAuditHttpEvent(aggregateType, aggregateId, revision, reasonCode, policyVersion, createdAt)

/**
 * encode은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
private fun VoucherStreamEvent.encode(): ByteArray =
    buildString {
        append("id: ").append(cursor).append('\n')
        append("event: ").append(event).append('\n')
        append("data: ").append(data.replace("\n", "")).append("\n\n")
    }.toUtf8Bytes()

/**
 * invalidCursor은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
private fun invalidCursor(): VoucherApiException =
    VoucherApiException("INVALID_EVENT_CURSOR", 400, "event cursor is invalid")
