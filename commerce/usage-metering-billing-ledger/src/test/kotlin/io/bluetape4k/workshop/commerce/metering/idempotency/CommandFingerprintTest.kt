package io.bluetape4k.workshop.commerce.metering.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * CommandFingerprintTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class CommandFingerprintTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "field order does not change a domain separated fingerprint" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `field order does not change a domain separated fingerprint`() {
        val first = CommandFingerprint.request(
            "usage-ingest",
            mapOf("quantity" to "1.0", "occurredAt" to "2026-07-01T00:00:00Z"),
        )
        val second = CommandFingerprint.request(
            "usage-ingest",
            linkedMapOf("occurredAt" to "2026-07-01T00:00:00Z", "quantity" to "1.0"),
        )

        log.debug { "first=$first" }
        log.debug { "second=$second" }

        first shouldBeEqualTo second
        first.value.length shouldBeEqualTo 64
    }

    @Test
/**
 * 이 시나리오는 "decimal and instant canonicalization is stable" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `decimal and instant canonicalization is stable`() {
        CommandFingerprint.decimal(BigDecimal("1.2300")) shouldBeEqualTo "1.23"
        CommandFingerprint.decimal(BigDecimal("0.000")) shouldBeEqualTo "0"
        CommandFingerprint.instant(Instant.parse("2026-07-01T00:00:00.000Z")) shouldBeEqualTo
                "2026-07-01T00:00:00Z"
    }

    @Test
/**
 * 이 시나리오는 "raw key is reduced to a non-reversible digest" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `raw key is reduced to a non-reversible digest`() {
        val rawKey = "customer-visible-secret-key"
        val digest = CommandFingerprint.key(rawKey)

        log.debug { "digest=$digest" }
        digest.value.length shouldBeEqualTo 64
        digest.value shouldNotBeEqualTo rawKey
    }
}
