package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class IdempotencyCleanupServiceTest {
    @Test
    fun `scheduled cleanup delegates a bounded batch at the current clock instant`() {
        val repository = mockk<HttpIdempotencyRepository>()
        val now = Instant.parse("2026-07-19T00:00:00Z")
        every { repository.deleteExpiredTerminal(now, 250) } returns 17
        val cleanup =
            IdempotencyCleanupService(
                repository,
                Clock.fixed(now, ZoneOffset.UTC),
                batchSize = 250
            )

        cleanup.deleteExpiredTerminal() shouldBeEqualTo 17

        verify(exactly = 1) { repository.deleteExpiredTerminal(now, 250) }
    }
}
