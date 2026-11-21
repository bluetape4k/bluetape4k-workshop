package io.bluetape4k.workshop.commerce.voucher.reconciliation

import io.bluetape4k.javatimes.requireZeroOrPositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.InboxStatus
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.ApplicationModuleListener
import java.io.Serializable
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * DelayedVoucherEvent은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
 */
internal data class DelayedVoucherEvent(
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `eventId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
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
     * `payloadDigest`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val payloadDigest: String,
    /**
     * `observedSequence`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val observedSequence: Long,
) {
    init {
        require(tenantId.isNotBlank() && tenantId.length <= 64) { "tenantId must contain 1..64 characters" }
        require(aggregateType.isNotBlank() && aggregateType.length <= 32) {
            "aggregateType must contain 1..32 characters"
        }
        require(payloadDigest.matches(Regex("[0-9a-fA-F]{64}"))) { "payloadDigest must be a SHA-256 hex digest" }
        require(observedSequence >= 0) { "observedSequence must not be negative" }
    }
}

/**
 * InboxOutcome은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
 */
internal enum class InboxOutcome {
    /**
     * `InboxOutcome.APPLIED`은 InboxOutcome 업무 상태의 `APPLIED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    APPLIED,
    /**
     * `InboxOutcome.IGNORED`은 InboxOutcome 업무 상태의 `IGNORED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    IGNORED,
    /**
     * `InboxOutcome.CONFLICT`은 InboxOutcome 업무 상태의 `CONFLICT` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로
     * 사용합니다.
     */
    CONFLICT,
    /**
     * `InboxOutcome.PENDING`은 InboxOutcome 업무 상태의 `PENDING` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    PENDING,
    /**
     * `InboxOutcome.FAILED`은 InboxOutcome 업무 상태의 `FAILED` 단계입니다. 허용된 상태 전이와 재시도·보상·응답 분기를 선택하는
     * 기준으로 사용합니다.
     */
    FAILED,
}

/**
 * InboxAcceptance은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
 */
internal data class InboxAcceptance(
    /**
     * `inboxId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val inboxId: Long,
    /**
     * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val outcome: InboxOutcome,
): Serializable

/**
 * ReconciliationResult은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
 */
internal data class ReconciliationResult(
    /**
     * `processed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val processed: Int,
    /**
     * `skipped`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val skipped: Int,
    /**
     * `failed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val failed: Int,
    /**
     * `lastCursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val lastCursor: String?,
    /**
     * `deadlineReached`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val deadlineReached: Boolean,
): Serializable

/** delayed event effect가 적용된 뒤에만 발행되는 안정적이고 민감하지 않은 event입니다. */
/** delayed event effect가 적용된 뒤에만 발행되는 안정적이고 민감하지 않은 event입니다. */
internal data class VoucherInboxAppliedEvent(
    /**
     * `eventId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `tenantId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
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
): Serializable

/**
 * VoucherEventPublisher은 프로모션 바우처 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface VoucherEventPublisher {
    /**
     * publish은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     * @param event 프로모션 바우처 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun publish(event: VoucherInboxAppliedEvent)

    companion object {
        val NONE = VoucherEventPublisher {}
    }
}

/**
 * SpringVoucherEventPublisher은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
 */
internal class SpringVoucherEventPublisher(
    /**
     * `events`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val events: ApplicationEventPublisher,
): VoucherEventPublisher {
    /**
     * publish은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     * @param event 프로모션 바우처 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun publish(event: VoucherInboxAppliedEvent) {
        events.publishEvent(event)
    }
}

/**
 * DelayedEventDecision은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
 */
internal sealed interface DelayedEventDecision: Serializable {
    /**
     * Apply은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     */
    data object Apply: DelayedEventDecision {
        /**
         * readResolve은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
         */
        private fun readResolve(): Any = Apply
    }

    /**
     * Ignore은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     */
    data object Ignore: DelayedEventDecision {
        /**
         * readResolve은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
         */
        private fun readResolve(): Any = Ignore
    }

    /**
     * Conflict은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     */
    data object Conflict: DelayedEventDecision {
        /**
         * readResolve은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
         */
        private fun readResolve(): Any = Conflict
    }

    /**
     * Retry은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     */
    data class Retry(val reasonCode: String): DelayedEventDecision {
        init {
            require(reasonCode.isNotBlank() && reasonCode.length <= 64) {
                "reasonCode must contain 1..64 characters"
            }
        }
    }
}

/**
 * VoucherDelayedEventHandler은 프로모션 바우처 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface VoucherDelayedEventHandler {
    /**
     * handle은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     * @param record 프로모션 바우처 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun handle(record: EventInboxRecord): DelayedEventDecision
}

/** durable application fixture입니다. 적용된 delayed event는 append-only audit effect 하나를 만듭니다. */
/** durable application fixture입니다. 적용된 delayed event는 append-only audit effect 하나를 만듭니다. */
internal class AuditingVoucherDelayedEventHandler(
    /**
     * `audits`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val audits: AuditRepository,
    /**
     * `events`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val events: VoucherEventPublisher = VoucherEventPublisher.NONE,
): VoucherDelayedEventHandler {

    /**
     * handle은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     * @param record 프로모션 바우처 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    override fun handle(record: EventInboxRecord): DelayedEventDecision {
        audits.append(
            AuditRecord(
                id = 0,
                tenantId = record.tenantId,
                campaignId = record.aggregateId,
                aggregateType = "DELAYED_${record.aggregateType}".take(32),
                aggregateId = record.aggregateId,
                revision = record.observedSequence,
                actorType = "EXTERNAL_EVENT",
                reasonCode = "DELAYED_EVENT_APPLIED",
                policyVersion = 0,
                correlationDigest = record.payloadDigest,
            ),
        )
        events.publish(
            VoucherInboxAppliedEvent(
                eventId = record.eventId,
                tenantId = record.tenantId,
                aggregateType = record.aggregateType,
                aggregateId = record.aggregateId,
                revision = record.observedSequence,
            ),
        )
        return DelayedEventDecision.Apply
    }
}

/** reconciliation transaction이 commit된 뒤 Modulith publication을 비동기로 완료합니다. */
/** reconciliation transaction이 commit된 뒤 Modulith publication을 비동기로 완료합니다. */
internal open class VoucherInboxAppliedEventListener {
    @ApplicationModuleListener
    /**
     * on은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     * @param event 프로모션 바우처 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    open fun on(event: VoucherInboxAppliedEvent) {
        log.info {
            "voucher_inbox_event_delivered eventId=${event.eventId} aggregateType=${event.aggregateType} " +
                    "aggregateId=${event.aggregateId} revision=${event.revision}"
        }
    }

    companion object: KLogging()
}

/** authoritative effect 이후, inbox finalization 이전에 둔 test seam입니다. */
/** authoritative effect 이후, inbox finalization 이전에 둔 test seam입니다. */
internal fun interface ReconciliationFaultInjector {
    /**
     * afterEffect은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     * @param record 프로모션 바우처 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun afterEffect(record: EventInboxRecord)

    companion object {
        val NONE = ReconciliationFaultInjector {}
    }
}

/**
 * transaction 하나당 inbox row 하나를 reconcile해 claim, effect, terminal outcome이 atomic하게 commit되도록 합니다.
 * Redis와 leader state는 decision에 절대 참여하지 않습니다.
 */
internal class VoucherReconciliationService(
    /**
     * `jdbc`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jdbc: VoucherJdbcExecutor,
    /**
     * `inbox`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val inbox: EventInboxRepository,
    /**
     * `handler`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val handler: VoucherDelayedEventHandler,
    /**
     * `clock`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
    /**
     * `transactionTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val transactionTimeout: Duration,
    /**
     * `claimOwner`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val claimOwner: String = "voucher-reconciliation",
    /**
     * `maxAttempts`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /**
     * `faultInjector`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val faultInjector: ReconciliationFaultInjector = ReconciliationFaultInjector.NONE,
) {

    companion object: KLogging() {
        /**
         * `DEFAULT_BATCH_SIZE`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private const val DEFAULT_BATCH_SIZE = 50
        /**
         * `MAX_BATCH_SIZE`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private const val MAX_BATCH_SIZE = 50
        /**
         * `DEFAULT_MAX_ATTEMPTS`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private const val DEFAULT_MAX_ATTEMPTS = 5
        /**
         * `MAX_BACKOFF_SHIFT`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val MAX_BACKOFF_SHIFT = 5
        /**
         * `DEFAULT_RUN_DEADLINE`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        private val DEFAULT_RUN_DEADLINE: Duration = 10.seconds()
    }

    init {
        require(!transactionTimeout.isNegative && !transactionTimeout.isZero) {
            "transactionTimeout must be positive"
        }
        require(claimOwner.isNotBlank() && claimOwner.length <= 128) { "claimOwner must contain 1..128 characters" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    /**
     * accept은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     * @param event 프로모션 바우처 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun accept(event: DelayedVoucherEvent): InboxAcceptance = jdbc.foregroundTransaction {
        val now = clock.instant()
        val insertion = inbox.insertIfAbsent(
            EventInboxRecord(
                id = 0,
                tenantId = event.tenantId,
                eventId = event.eventId,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                payloadDigest = event.payloadDigest.lowercase(),
                observedSequence = event.observedSequence,
                status = InboxStatus.PENDING,
                attempt = 0,
                nextAttemptAt = now,
                claimOwner = null,
                claimUntil = null,
            ),
        )
        if (!insertion.inserted) {
            val outcome = if (insertion.record.payloadDigest == event.payloadDigest.lowercase()) {
                InboxOutcome.IGNORED
            } else {
                InboxOutcome.CONFLICT
            }
            log.info { "voucher_inbox_duplicate outcome=$outcome inboxId=${insertion.record.id}" }
            return@foregroundTransaction InboxAcceptance(insertion.record.id, outcome)
        }

        val claimed = inbox.claimById(insertion.record.id, now, claimOwner, transactionTimeout)
        val completed = applyClaimed(claimed, now, now + transactionTimeout)
        InboxAcceptance(completed.record.id, completed.outcome)
    }

    /**
     * runBatch은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     */
    fun runBatch(
        batchSize: Int = DEFAULT_BATCH_SIZE,
        deadline: Duration = DEFAULT_RUN_DEADLINE,
    ): ReconciliationResult {
        batchSize.requireInRange(1, MAX_BATCH_SIZE, "batchSize")
        deadline.requireZeroOrPositiveDuration("deadline")

        if (deadline.isZero) {
            return ReconciliationResult(0, 0, 0, null, true)
        }

        /**
         * `runEndsAt`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val runEndsAt = clock.instant().plus(deadline)
        /**
         * `processed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        var processed = 0
        /**
         * `skipped`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        var skipped = 0
        /**
         * `failed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        var failed = 0
        /**
         * `lastCursor`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        var lastCursor: String? = null
        /**
         * `deadlineReached`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        var deadlineReached = false

        while (processed + skipped + failed < batchSize) {
            /**
             * `remaining`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val remaining = Duration.between(clock.instant(), runEndsAt)
            if (!remaining.isPositive()) {
                deadlineReached = true
                break
            }
            /**
             * `rowTimeout`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val rowTimeout = minOf(remaining, transactionTimeout)
            /**
             * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = try {
                jdbc.workerTransaction(rowTimeout) {
                    ensureBefore(runEndsAt)
                    /**
                     * `claimed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                     *
                     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                     */
                    val claimed = inbox.claimNext(clock.instant(), claimOwner, rowTimeout)
                        ?: return@workerTransaction RowResult.NoWork
                    applyClaimed(claimed, clock.instant(), runEndsAt)
                }
            } catch (_: ReconciliationDeadlineExceeded) {
                deadlineReached = true
                break
            }
            when (result) {
                RowResult.NoWork       -> break
                is RowResult.Completed -> {
                    lastCursor = cursorOf(result.record.id)
                    when (result.outcome) {
                        InboxOutcome.APPLIED                      -> processed++
                        InboxOutcome.IGNORED, InboxOutcome.CONFLICT -> skipped++
                        InboxOutcome.PENDING, InboxOutcome.FAILED -> failed++
                    }
                }
            }
        }

        /**
         * `result`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val result = ReconciliationResult(processed, skipped, failed, lastCursor, deadlineReached)
        log.info {
            "voucher_reconciliation_completed processed=$processed skipped=$skipped failed=$failed " +
                    "deadlineReached=$deadlineReached"
        }
        return result
    }

    /**
     * applyClaimed은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     */
    private fun applyClaimed(
        claimed: EventInboxRecord,
        now: Instant,
        runEndsAt: Instant,
    ): RowResult.Completed {
        inbox.lockAggregate(claimed)
        ensureBefore(runEndsAt)
        val latestApplied = inbox.latestAppliedSequence(claimed)
        val decision = when {
            latestApplied == claimed.observedSequence                         -> DelayedEventDecision.Ignore
            latestApplied != null && latestApplied > claimed.observedSequence -> DelayedEventDecision.Conflict
            else                                                              -> handler.handle(claimed)
        }
        if (decision is DelayedEventDecision.Apply) {
            faultInjector.afterEffect(claimed)
            ensureBefore(runEndsAt)
        }
        val completed = when (decision) {
            DelayedEventDecision.Apply    -> inbox.complete(claimed.id, InboxStatus.APPLIED)
            DelayedEventDecision.Ignore   -> inbox.complete(claimed.id, InboxStatus.IGNORED)
            DelayedEventDecision.Conflict -> inbox.complete(claimed.id, InboxStatus.CONFLICT)
            is DelayedEventDecision.Retry -> {
                val backoff = backoffFor(claimed.attempt + 1)
                log.warn {
                    "voucher_inbox_retry inboxId=${claimed.id} attempt=${claimed.attempt + 1} " +
                            "reason=${decision.reasonCode}"
                }
                inbox.retry(claimed, now, now.plus(backoff), maxAttempts)
            }
        }
        return RowResult.Completed(completed, completed.status.toOutcome())
    }

    /**
     * ensureBefore은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     * @param deadline 프로모션 바우처 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun ensureBefore(deadline: Instant) {
        if (!clock.instant().isBefore(deadline)) {
            throw ReconciliationDeadlineExceeded()
        }
    }

    /**
     * backoffFor은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     * @param attempt 프로모션 바우처 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun backoffFor(attempt: Int): Duration =
        (1L shl (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)).seconds()

    /**
     * cursorOf은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     * @param id 프로모션 바우처 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    private fun cursorOf(id: Long): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(id).array())

/**
 * toOutcome은 프로모션 바우처 업무 흐름에서 입력을 검증하거나 확정 상태를 변환하는 단계입니다.
 *
 * 호출 전에는 인증 주체·tenant 범위·멱등 키·기대 버전을 확인하고, 호출 후에는 commit된 source-of-truth와 비동기 알림을 구분하여 재시도·보상·감사
 * 처리를 일관되게 수행해야 합니다.
 */
    private fun InboxStatus.toOutcome(): InboxOutcome =
        when (this) {
            InboxStatus.APPLIED  -> InboxOutcome.APPLIED
            InboxStatus.IGNORED  -> InboxOutcome.IGNORED
            InboxStatus.CONFLICT -> InboxOutcome.CONFLICT
            InboxStatus.PENDING, InboxStatus.CLAIMED -> InboxOutcome.PENDING
            InboxStatus.FAILED   -> InboxOutcome.FAILED
        }

    /**
     * RowResult은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     */
    private sealed interface RowResult {
        /**
         * NoWork은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
         *
         * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
         */
        data object NoWork: RowResult

        /**
         * Completed은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
         *
         * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
         */
        data class Completed(
            /**
             * `record`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
             *
             * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
             */
            val record: EventInboxRecord,
            /**
             * `outcome`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
             *
             * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
             */
            val outcome: InboxOutcome,
        ): RowResult
    }

    /**
     * ReconciliationDeadlineExceeded은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
     * 타입의 규칙에
     * 맞춰 해석합니다.
     *
     * 업무 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 단계를 진행해야 합니다.
     */
    private class ReconciliationDeadlineExceeded: RuntimeException()
}
