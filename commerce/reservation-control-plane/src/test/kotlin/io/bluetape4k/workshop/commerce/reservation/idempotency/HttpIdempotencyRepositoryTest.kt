package io.bluetape4k.workshop.commerce.reservation.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.workshop.commerce.reservation.persistence.reservationTables
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class HttpIdempotencyRepositoryTest {
    private val repository = HttpIdempotencyRepository()
    private val now = Instant.parse("2026-07-19T00:00:00Z")
    private val scope =
        IdempotencyScope(
            tenantId = "tenant-a",
            operation = "create-hold",
            keyDigest = IdempotencyFingerprint.key("tenant-a", "create-hold", "key-a")
        )
    private val fingerprint = IdempotencyFingerprint.request("create-hold", "resource=room-a")

    @Test
    fun `new owner finalizes and the terminal response is replayed on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val owner = UUID.randomUUID()
            val acquired = repository.acquire(scope, fingerprint, owner, now) as AcquireResult.New

            repository.finalize(acquired.record.id, owner, 201, "{\"holdId\":\"h-1\"}", failed = false) shouldBeEqualTo
                true
            repository.acquire(scope, fingerprint, UUID.randomUUID(), now.plusSeconds(1)) shouldBeEqualTo
                AcquireResult.Replay(201, "{\"holdId\":\"h-1\"}", failed = false)
        }
    }

    @Test
    fun `same scoped key with a different payload is rejected deterministically`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            repository.acquire(scope, fingerprint, UUID.randomUUID(), now)

            repository.acquire(
                scope,
                IdempotencyFingerprint.request("create-hold", "resource=room-b"),
                UUID.randomUUID(),
                now.plusSeconds(1)
            ) shouldBeEqualTo AcquireResult.FingerprintConflict
        }
    }

    @Test
    fun `active owner returns the remaining lease as in progress`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            repository.acquire(scope, fingerprint, UUID.randomUUID(), now)

            repository.acquire(scope, fingerprint, UUID.randomUUID(), now.plusSeconds(30)) shouldBeEqualTo
                AcquireResult.InProgress(Duration.ofSeconds(60))
        }
    }

    @Test
    fun `expired owner is taken over and stale finalize compare and set is rejected`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val staleOwner = UUID.randomUUID()
            val original = repository.acquire(scope, fingerprint, staleOwner, now) as AcquireResult.New
            val replacementOwner = UUID.randomUUID()

            val takeover =
                repository.acquire(scope, fingerprint, replacementOwner, now.plusSeconds(91)) as AcquireResult.Takeover

            takeover.record.ownerToken shouldBeEqualTo replacementOwner
            repository.finalize(original.record.id, staleOwner, 201, "stale", failed = false) shouldBeEqualTo false
            repository.finalize(original.record.id, replacementOwner, 201, "fresh", failed = false) shouldBeEqualTo true
        }
    }
}
