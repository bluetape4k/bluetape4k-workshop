package io.bluetape4k.workshop.commerce.usagebilling.usage.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.AcceptUsageCommand
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.MissingPriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceJournal
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceResult
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageSourceConflict
import io.bluetape4k.workshop.commerce.usagebilling.usage.integration.UsageIntegrationEnvelope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.*
/**
 * UsageCommandService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property journal 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property nextEventId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 */


@Service
class UsageCommandService(
    /**
     * `journal`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val journal: UsageAcceptanceJournal,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
    /**
     * `nextEventId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    private val nextEventId: () -> UUID = Uuid.V7::nextId,
) {
    companion object: KLogging()

    /**
     * `accept`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = accept(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun accept(command: AcceptUsageCommand): UsageAcceptanceResult {
        val fingerprint = command.fingerprint()

        journal.findUsage(command.tenantId, command.sourceSystem, command.sourceEventId)
            ?.let { existing ->
                if (existing.fingerprint != fingerprint) throw UsageSourceConflict()
                return UsageAcceptanceResult(existing.usageId, existing.eventId, replayed = true)
            }

        val evidence = journal.priceEvidence(command.tenantId, command.meterCode, command.currency)
            ?: throw MissingPriceEvidence()

        val now = clock.instant()
        val eventId = nextEventId()
        val usage = UsageRecord(
            usageId = Uuid.V7.nextId(),
            eventId = eventId,
            tenantId = command.tenantId,
            sourceSystem = command.sourceSystem,
            sourceEventId = command.sourceEventId,
            fingerprint = fingerprint,
            meterCode = command.meterCode,
            currency = command.currency,
            quantity = command.quantity,
            unitPrice = evidence.unitPrice,
            occurredAt = command.occurredAt,
        )
        val envelope = UsageIntegrationEnvelope.create(
            eventId = eventId,
            eventType = "UsageAccepted",
            schemaVersion = 1,
            tenantId = command.tenantId,
            aggregateId = command.sourceEventId,
            aggregateVersion = 1,
            payload = usageAcceptedPayload(usage),
            occurredAt = command.occurredAt,
            recordedAt = now,
        )
        val wirePayload = envelope.wirePayload()
        journal.append(
            usage,
            UsageOutboxRecord(
                eventId,
                envelope.eventType,
                envelope.partitionKey(),
                wirePayload,
                envelope.wirePayloadDigest(),
                now,
            ),
        )
        return UsageAcceptanceResult(usage.usageId, eventId, replayed = false)
    }

    /**
     * usageAcceptedPayload은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param usage 업무 처리에 필요한 usage 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun usageAcceptedPayload(usage: UsageRecord): String =
        """{"usageId":"${usage.usageId}",""" +
                """"meterCode":"${usage.meterCode}",""" +
                """"currency":"${usage.currency}",""" +
                """"quantity":"${usage.quantity}",""" +
                """"unitPrice":"${usage.unitPrice}"}"""
}
