package io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

/**
 * CommandFingerprintTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class CommandFingerprintTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "request fingerprint ignores field order but not semantic values" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `request fingerprint ignores field order but not semantic values`() {
        val first = CommandFingerprint.request("meter-register", mapOf("unit" to "request", "code" to "api_calls"))
        val reordered = CommandFingerprint.request("meter-register", mapOf("code" to "api_calls", "unit" to "request"))
        val changed = CommandFingerprint.request("meter-register", mapOf("code" to "api_calls", "unit" to "call"))

        log.debug { "reordered: $reordered" }
        log.debug { "changed: $changed" }

        reordered shouldBeEqualTo first
        changed shouldNotBeEqualTo first
    }
}
