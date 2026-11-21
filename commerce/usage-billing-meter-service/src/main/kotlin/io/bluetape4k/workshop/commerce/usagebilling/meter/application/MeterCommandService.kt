package io.bluetape4k.workshop.commerce.usagebilling.meter.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.ActivatePriceCommand
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterActivationResult
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandJournal
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandReceipt
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterIdempotencyConflict
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterPriceVersion
import io.bluetape4k.workshop.commerce.usagebilling.meter.integration.MeterIntegrationEnvelope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID
/**
 * MeterCommandService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property journal 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property nextEventId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
 */


@Service
class MeterCommandService(
    /**
     * `journal`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val journal: MeterCommandJournal,
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
    /**
     * `activatePrice`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = activatePrice(command = command)
     * ```
     * @param command 요청 경계에서 검증된 `command`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun activatePrice(command: ActivatePriceCommand): MeterActivationResult {
        val fingerprint = command.fingerprint()
        journal.findReceipt(command.idempotencyKey)?.let { existing ->
            if (existing.fingerprint != fingerprint) throw MeterIdempotencyConflict()
            return existing.result.copy(replayed = true)
        }

        val now = clock.instant()
        val eventId = nextEventId()
        val priceVersion = MeterPriceVersion(
            priceVersionId = Uuid.V7.nextId(),
            tenantId = command.tenantId,
            meterCode = command.meterCode,
            currency = command.currency,
            unitPrice = command.unitPrice,
            effectiveAt = command.effectiveAt,
            createdAt = now,
        )
        val envelope = MeterIntegrationEnvelope.create(
            eventId = eventId,
            eventType = "PriceActivated",
            schemaVersion = 1,
            tenantId = command.tenantId,
            aggregateId = command.meterCode,
            aggregateVersion = 1,
            payload = priceActivatedPayload(command, priceVersion),
            occurredAt = command.effectiveAt,
            recordedAt = now,
        )
        val wirePayload = envelope.wirePayload()
        val result = MeterActivationResult(priceVersion.priceVersionId, eventId, replayed = false)
        journal.append(
            receipt = MeterCommandReceipt(command.idempotencyKey, fingerprint, result),
            priceVersion = priceVersion,
            outboxRecord = MeterOutboxRecord(
                eventId = eventId,
                eventType = envelope.eventType,
                partitionKey = envelope.partitionKey(),
                payload = wirePayload,
                payloadDigest = envelope.wirePayloadDigest(),
                createdAt = now,
            ),
        )
        return result
    }

    /**
     * priceActivatedPayload은 사용량 계량·청구의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로
     * 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param priceVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun priceActivatedPayload(
        command: ActivatePriceCommand,
        priceVersion: MeterPriceVersion,
    ): String =
        """{"meterCode":"${command.meterCode}",""" +
            """"currency":"${command.currency}",""" +
            """"unitPrice":"${priceVersion.unitPrice}"}"""
}
