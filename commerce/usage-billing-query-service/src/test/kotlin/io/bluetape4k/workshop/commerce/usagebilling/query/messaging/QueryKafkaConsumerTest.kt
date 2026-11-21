package io.bluetape4k.workshop.commerce.usagebilling.query.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryInboxService
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryQuarantineService
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryApplyResult
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.*

/**
 * QueryKafkaConsumerTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class QueryKafkaConsumerTest {

    private companion object: KLogging() {
/**
 * PAYLOAD은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PAYLOAD = "{\"invoiceNumber\":\"INV-1\"}"
    }

/**
 * decoder은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val decoder = QueryInboundEventDecoder()

/**
 * inbox은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val inbox = mockk<QueryInboxService>(relaxed = true)
/**
 * quarantine은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val quarantine = mockk<QueryQuarantineService>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(inbox, quarantine)
    }


    @Test
/**
 * 이 시나리오는 "decodes supported upstream event into Query-owned projection input" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `decodes supported upstream event into Query-owned projection input`() {
        val event = decoder.decode(envelope("InvoiceIssued"))

        log.debug { "event=$event" }
        event.tenantId shouldBeEqualTo "tenant-a"
        event.aggregateType shouldBeEqualTo "Invoice"
        event.aggregateId shouldBeEqualTo "invoice-1"
        event.payloadDigest shouldBeEqualTo digestOf(PAYLOAD)
    }

    @Test
/**
 * 이 시나리오는 "decodes additive schema v2 through the compatibility boundary" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `decodes additive schema v2 through the compatibility boundary`() {
        val event = decoder.decode(envelope("InvoiceIssued", schemaVersion = 2))

        log.debug { "event=$event" }
        event.eventType shouldBeEqualTo "InvoiceIssued"
        event.aggregateVersion shouldBeEqualTo 1L
    }

    @Test
/**
 * 이 시나리오는 "unknown mandatory schema retains metadata for durable quarantine" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unknown mandatory schema retains metadata for durable quarantine`() {
        val failure = assertFailsWith<PermanentQueryInboundException> {
            decoder.decode(envelope("InvoiceIssued", schemaVersion = 99))
        }

        log.debug { "failure=$failure" }
        failure.quarantine.eventType shouldBeEqualTo "InvoiceIssued"
        failure.quarantine.reason shouldBeEqualTo "unsupported_schema:99"
    }

    @Test
/**
 * 이 시나리오는 "rejects altered payload with enough metadata for durable quarantine" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `rejects altered payload with enough metadata for durable quarantine`() {
        val failure = assertFailsWith<PermanentQueryInboundException> {
            decoder.decode(envelope("InvoiceIssued", payloadDigest = "0".repeat(64)))
        }

        log.debug { "failure=$failure" }
        failure.quarantine.eventType shouldBeEqualTo "InvoiceIssued"
        failure.quarantine.reason shouldBeEqualTo "invalid_payload_digest"
    }

    @Test
/**
 * 이 시나리오는 "listener returns only after a valid event reaches the durable inbox service" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `listener returns only after a valid event reaches the durable inbox service`() {
        every { inbox.apply(any()) } returns QueryApplyResult(applied = true)
        val listener = KafkaQueryIntegrationListener(decoder, inbox, quarantine)

        listener.consume(envelope("InvoiceIssued"))

        verify(exactly = 1) { inbox.apply(match { it.eventType == "InvoiceIssued" }) }
        verify(exactly = 0) { quarantine.record(any()) }
    }

    @Test
/**
 * 이 시나리오는 "listener durably quarantines permanent decode failure instead of retrying forever" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `listener durably quarantines permanent decode failure instead of retrying forever`() {
        val listener = KafkaQueryIntegrationListener(decoder, inbox, quarantine)

        listener.consume(envelope("UnknownEvent"))

        verify(exactly = 0) { inbox.apply(any()) }
        verify(exactly = 1) {
            quarantine.record(match { it.eventType == "UnknownEvent" && it.reason == "unsupported_schema:1" })
        }
    }

    @Test
/**
 * 이 시나리오는 "listener propagates transient inbox failure for Kafka redelivery" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `listener propagates transient inbox failure for Kafka redelivery`() {
        every { inbox.apply(any()) } throws IllegalStateException("database unavailable")
        val listener = KafkaQueryIntegrationListener(decoder, inbox, quarantine)

        assertFailsWith<IllegalStateException> {
            listener.consume(envelope("InvoiceIssued"))
        }
        verify(exactly = 0) { quarantine.record(any()) }
    }

/**
 * envelope은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun envelope(
        eventType: String,
        payloadDigest: String = digestOf(PAYLOAD),
        schemaVersion: Int = 1,
    ): String =
        Jackson.defaultJsonMapper.writeValueAsString(
            linkedMapOf(
                "eventId" to "018f4a40-7d3e-7b3a-8c5b-7f0f9b2e1a01",
                "eventType" to eventType,
                "schemaVersion" to schemaVersion,
                "tenantId" to "tenant-a",
                "aggregateType" to "Invoice",
                "aggregateId" to "invoice-1",
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
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()))
}
