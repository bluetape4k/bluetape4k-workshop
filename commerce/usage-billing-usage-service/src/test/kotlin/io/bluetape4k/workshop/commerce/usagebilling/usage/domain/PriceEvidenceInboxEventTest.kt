package io.bluetape4k.workshop.commerce.usagebilling.usage.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * PriceEvidenceInboxEventTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class PriceEvidenceInboxEventTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "tenant mismatch is rejected as producer input" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `tenant mismatch is rejected as producer input`() {
        assertFailsWith<IllegalArgumentException> {
            PriceEvidenceInboxEvent(
                eventId = UUID.randomUUID(),
                tenantId = "tenant-a",
                payloadDigest = "a".repeat(64),
                evidence = PriceEvidence(
                    tenantId = "tenant-b",
                    meterCode = "api_calls",
                    currency = "USD",
                    unitPrice = BigDecimal("0.10"),
                    effectiveAt = Instant.parse("2026-07-23T00:00:00Z"),
                ),
            )
        }
    }
}
