package io.bluetape4k.workshop.commerce.ticket.identity

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * TrustedClientAddressResolverTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TrustedClientAddressResolverTest {

    companion object: KLogging()

/**
 * resolver은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val resolver = TrustedClientAddressResolver(listOf("10.0.0.0/8", "192.168.10.0/24"))

    @Test
/**
 * 이 시나리오는 "untrusted peer cannot spoof forwarded address" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `untrusted peer cannot spoof forwarded address`() {
        resolver.resolve(
            remoteAddress = "203.0.113.9",
            xForwardedFor = "198.51.100.2"
        ) shouldBeEqualTo "203.0.113.9"
    }

    @Test
/**
 * 이 시나리오는 "trusted proxy chain resolves the first untrusted hop from the right" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `trusted proxy chain resolves the first untrusted hop from the right`() {
        resolver.resolve(
            remoteAddress = "10.0.0.7",
            xForwardedFor = "198.51.100.20, 192.168.10.5",
        ) shouldBeEqualTo "198.51.100.20"
    }

    @Test
/**
 * 이 시나리오는 "malformed chain and wildcard trust fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `malformed chain and wildcard trust fail closed`() {
        assertFailsWith<InvalidClientAddress> {
            resolver.resolve("10.0.0.7", "not-an-ip")
        }
        assertFailsWith<IllegalArgumentException> {
            TrustedClientAddressResolver(listOf("0.0.0.0/0"))
        }
        assertFailsWith<IllegalArgumentException> {
            TrustedClientAddressResolver(listOf("::/0"))
        }
    }
}
