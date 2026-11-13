package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class IdempotencyCleanupServiceTest {

    companion object: KLogging()

    private val repository = mockk<HttpIdempotencyRepository>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(repository)
    }

    @Test
    fun `scheduled cleanup delegates a bounded batch at the current clock instant`() {
        val now = Instant.parse("2026-07-19T00:00:00Z")
        every { repository.deleteExpiredTerminal(now, 250) } returns 17

        val cleanup = IdempotencyCleanupService(
            repository,
            Clock.fixed(now, ZoneOffset.UTC),
            batchSize = 250
        )
        cleanup.deleteExpiredTerminal() shouldBeEqualTo 17

        verify(exactly = 1) { repository.deleteExpiredTerminal(now, 250) }
    }
}
