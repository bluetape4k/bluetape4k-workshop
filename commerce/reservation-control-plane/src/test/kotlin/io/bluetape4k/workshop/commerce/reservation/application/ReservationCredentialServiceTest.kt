package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import org.junit.jupiter.api.Test

class ReservationCredentialServiceTest {
    private val service = ReservationCredentialService("0123456789abcdef0123456789abcdef")

    @Test
    fun `owner and idempotency digests are domain separated and constant-time comparable`() {
        val owner = service.ownerDigest("reservation-owner-token-with-256-bits-of-entropy")
        val command = service.idempotencyDigest("demo", "hold", "idempotency-key-128-bits")

        owner shouldNotBeEqualTo command
        service.matchesOwner("reservation-owner-token-with-256-bits-of-entropy", owner).shouldBeTrue()
        service.matchesOwner("another-owner-token-with-256-bits-of-entropy", owner).shouldBeFalse()
    }
}
