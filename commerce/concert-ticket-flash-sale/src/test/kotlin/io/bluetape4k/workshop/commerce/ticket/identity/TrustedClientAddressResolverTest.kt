package io.bluetape4k.workshop.commerce.ticket.identity

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

internal class TrustedClientAddressResolverTest {
    private val resolver = TrustedClientAddressResolver(listOf("10.0.0.0/8", "192.168.10.0/24"))

    @Test
    fun `untrusted peer cannot spoof forwarded address`() {
        resolver.resolve("203.0.113.9", "198.51.100.2") shouldBeEqualTo "203.0.113.9"
    }

    @Test
    fun `trusted proxy chain resolves the first untrusted hop from the right`() {
        resolver.resolve(
            remoteAddress = "10.0.0.7",
            xForwardedFor = "198.51.100.20, 192.168.10.5",
        ) shouldBeEqualTo "198.51.100.20"
    }

    @Test
    fun `malformed chain and wildcard trust fail closed`() {
        assertFailsWith<InvalidClientAddress> { resolver.resolve("10.0.0.7", "not-an-ip") }
        assertFailsWith<IllegalArgumentException> { TrustedClientAddressResolver(listOf("0.0.0.0/0")) }
        assertFailsWith<IllegalArgumentException> { TrustedClientAddressResolver(listOf("::/0")) }
    }
}
