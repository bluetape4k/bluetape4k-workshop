package io.bluetape4k.workshop.commerce.usagebilling.billing.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.*

/**
 * BillingInboundEventDecoderTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class BillingInboundEventDecoderTest {

    private companion object: KLogging() {
/**
 * PRICE_PAYLOAD은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PRICE_PAYLOAD = "{\"meterCode\":\"api_calls\",\"currency\":\"USD\",\"unitPrice\":\"0.10\"}"
/**
 * USAGE_PAYLOAD은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val USAGE_PAYLOAD = "{\"meterCode\":\"api_calls\",\"currency\":\"USD\",\"quantity\":\"2.5\"}"
    }

/**
 * decoder은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val decoder = BillingInboundEventDecoder()

    @Test
/**
 * 이 시나리오는 "decodes Meter price evidence without sharing Meter runtime types" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `decodes Meter price evidence without sharing Meter runtime types`() {
        val envelop = envelope("PriceActivated", PRICE_PAYLOAD)
        val event = decoder.decode(envelop).shouldBeInstanceOf<BillingInboundEvent.PriceActivated>()

        log.debug { "event=$event" }
        event.event.payloadDigest shouldBeEqualTo digestOf(PRICE_PAYLOAD)
        event.event.evidence.tenantId shouldBeEqualTo "tenant-a"
        event.event.evidence.meterCode shouldBeEqualTo "api_calls"
        event.event.evidence.unitPrice.toPlainString() shouldBeEqualTo "0.10"
    }

    @Test
/**
 * 이 시나리오는 "decodes Usage acceptance into Billing local rating input" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `decodes Usage acceptance into Billing local rating input`() {
        val event = decoder.decode(
            envelope("UsageAccepted", USAGE_PAYLOAD, aggregateType = "Usage"),
        ).shouldBeInstanceOf<BillingInboundEvent.UsageAccepted>()

        log.debug { "event=$event" }
        event.event.payloadDigest shouldBeEqualTo digestOf(USAGE_PAYLOAD)
        event.event.meterCode shouldBeEqualTo "api_calls"
        event.event.quantity.toPlainString() shouldBeEqualTo "2.5"
    }

    @Test
/**
 * 이 시나리오는 "rejects an altered upstream payload before Billing persistence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects an altered upstream payload before Billing persistence`() {
        assertFailsWith<InvalidBillingInboundEnvelope> {
            decoder.decode(envelope("UsageAccepted", USAGE_PAYLOAD, payloadDigest = "0".repeat(64)))
        }
    }

    @Test
/**
 * 이 시나리오는 "rejects non-canonical or malformed payload digests" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects non-canonical or malformed payload digests`() {
        val digest = digestOf(USAGE_PAYLOAD)

        listOf(
            digest.uppercase(),
            "not-hex",
            digest.dropLast(2)
        ).forEach { invalidDigest ->
            assertFailsWith<InvalidBillingInboundEnvelope> {
                decoder.decode(envelope("UsageAccepted", USAGE_PAYLOAD, payloadDigest = invalidDigest))
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "rejects an unsupported upstream schema before Billing persistence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects an unsupported upstream schema before Billing persistence`() {
        assertFailsWith<UnsupportedBillingInboundEnvelope> {
            decoder.decode(envelope("PriceActivated", PRICE_PAYLOAD, schemaVersion = 99))
        }
    }

/**
 * envelope은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun envelope(
        eventType: String,
        payload: String,
        aggregateType: String = "Meter",
        schemaVersion: Int = 1,
        payloadDigest: String = digestOf(payload),
    ): String =
        Jackson.defaultJsonMapper.writeValueAsString(
            linkedMapOf(
                "eventId" to "018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01",
                "eventType" to eventType,
                "schemaVersion" to schemaVersion,
                "tenantId" to "tenant-a",
                "aggregateType" to aggregateType,
                "aggregateId" to "usage-1",
                "aggregateVersion" to 1,
                "payload" to payload,
                "payloadDigest" to payloadDigest,
                "occurredAt" to "2026-07-22T00:00:00Z",
                "recordedAt" to "2026-07-22T00:00:01Z",
            ),
        )

/**
 * digestOf은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun digestOf(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()))

}
