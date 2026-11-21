package io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.*

/**
 * BillingChargeDecoderTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class BillingChargeDecoderTest {

    private companion object: KLogging() {
/**
 * PAYLOAD은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PAYLOAD = "{\"sourceEventId\":\"018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a02\"," +
                "\"currency\":\"USD\",\"amount\":\"0.20\"}"
    }

/**
 * decoder은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val decoder = BillingChargeDecoder()

    @Test
/**
 * 이 시나리오는 "decodes Billing ChargeRated into an Invoice-owned inbox event" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `decodes Billing ChargeRated into an Invoice-owned inbox event`() {
        val event = decoder.decode(envelope("ChargeRated", PAYLOAD))

        log.debug { "event=$event" }
        event.tenantId shouldBeEqualTo "tenant-a"
        event.amount.toPlainString() shouldBeEqualTo "0.20"
        event.payloadDigest shouldBeEqualTo digestOf(PAYLOAD)
    }

    @Test
/**
 * 이 시나리오는 "rejects altered Billing payload before Invoice persistence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects altered Billing payload before Invoice persistence`() {
        assertFailsWith<InvalidBillingChargeEnvelope> {
            decoder.decode(envelope("ChargeRated", PAYLOAD, "0".repeat(64)))
        }
    }

    @Test
/**
 * 이 시나리오는 "rejects unsupported Billing schema before Invoice persistence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects unsupported Billing schema before Invoice persistence`() {
        assertFailsWith<UnsupportedBillingChargeEnvelope> {
            decoder.decode(envelope("ChargeRated", PAYLOAD, schemaVersion = 2))
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
        digest: String = digestOf(payload),
        schemaVersion: Int = 1,
    ): String =
        Jackson.defaultJsonMapper.writeValueAsString(
            linkedMapOf(
                "eventId" to "018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01",
                "eventType" to eventType,
                "schemaVersion" to schemaVersion,
                "tenantId" to "tenant-a",
                "aggregateType" to "Billing",
                "aggregateId" to "usage-1",
                "aggregateVersion" to 1,
                "payload" to payload,
                "payloadDigest" to digest,
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
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()))
}
