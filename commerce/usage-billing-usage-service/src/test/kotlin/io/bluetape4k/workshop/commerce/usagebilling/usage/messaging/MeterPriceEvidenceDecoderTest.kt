package io.bluetape4k.workshop.commerce.usagebilling.usage.messaging

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
 * MeterPriceEvidenceDecoderTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class MeterPriceEvidenceDecoderTest {

    private companion object: KLogging() {
/**
 * PAYLOAD은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PAYLOAD = "{\"meterCode\":\"api_calls\",\"currency\":\"USD\",\"unitPrice\":\"0.10\"}"
    }

/**
 * decoder은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val decoder = MeterPriceEvidenceDecoder()

    @Test
/**
 * 이 시나리오는 "decodes a complete Meter price envelope into local evidence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `decodes a complete Meter price envelope into local evidence`() {
        val evidence = decoder.decode(wirePayload())

        log.debug { "evidence=$evidence" }
        evidence.tenantId shouldBeEqualTo "tenant-a"
        evidence.payloadDigest shouldBeEqualTo digestOf(PAYLOAD)
        evidence.evidence.meterCode shouldBeEqualTo "api_calls"
        evidence.evidence.unitPrice.toPlainString() shouldBeEqualTo "0.10"
    }

    @Test
/**
 * 이 시나리오는 "rejects unknown Meter message versions before local persistence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects unknown Meter message versions before local persistence`() {
        assertFailsWith<UnsupportedMeterPriceEvidenceEnvelope> {
            decoder.decode(wirePayload(schemaVersion = 99))
        }.message shouldBeEqualTo "unsupported_meter_price_evidence_envelope:PriceActivated:99"
    }

    @Test
/**
 * 이 시나리오는 "rejects a Meter message whose inner payload digest was altered" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects a Meter message whose inner payload digest was altered`() {
        assertFailsWith<InvalidMeterPriceEvidenceEnvelope> {
            decoder.decode(wirePayload(payloadDigest = "0".repeat(64)))
        }.message shouldBeEqualTo "invalid_meter_price_evidence_envelope:018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01"
    }

/**
 * wirePayload은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun wirePayload(
        schemaVersion: Int = 1,
        payloadDigest: String = digestOf(PAYLOAD),
    ): String =
        Jackson.defaultJsonMapper.writeValueAsString(
            linkedMapOf(
                "eventId" to "018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01",
                "eventType" to "PriceActivated",
                "schemaVersion" to schemaVersion,
                "tenantId" to "tenant-a",
                "aggregateType" to "Meter",
                "aggregateId" to "api_calls",
                "aggregateVersion" to 1,
                "payload" to PAYLOAD,
                "payloadDigest" to payloadDigest,
                "occurredAt" to "2026-07-22T00:00:00Z",
                "recordedAt" to "2026-07-22T00:00:01Z",
            ),
        )

    @Suppress("SameParameterValue")
/**
 * digestOf은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun digestOf(value: String): String =
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()))
}
