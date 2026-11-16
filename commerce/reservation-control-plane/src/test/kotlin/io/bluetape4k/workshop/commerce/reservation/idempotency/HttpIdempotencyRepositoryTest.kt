package io.bluetape4k.workshop.commerce.reservation.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.reservation.persistence.reservationTables
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

internal class HttpIdempotencyRepositoryTest {

    companion object: KLogging()

    private val repository = HttpIdempotencyRepository()
    private val now = Instant.parse("2026-07-19T00:00:00Z")
    private val scope = IdempotencyScope(
        tenantId = "tenant-a",
        operation = "create-hold",
        keyDigest = IdempotencyFingerprint.key("tenant-a", "create-hold", "key-a")
    )
    private val fingerprint: String =
        IdempotencyFingerprint.request("create-hold", "resource=room-a")

    @Test
    fun `new owner finalizes and the terminal response is replayed on PostgreSQL`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val owner = UUID.randomUUID()

            val acquired = repository.acquire(
                scope = scope,
                requestFingerprint = fingerprint,
                ownerToken = owner,
                now = now
            ).shouldBeInstanceOf<AcquireResult.New>()

            repository.finalize(
                id = acquired.record.id,
                ownerToken = owner,
                responseStatus = 201,
                responseBody = "{\"holdId\":\"h-1\"}",
                failed = false
            ).shouldBeTrue()

            repository.acquire(
                scope = scope,
                requestFingerprint = fingerprint,
                ownerToken = UUID.randomUUID(),
                now = now + 1.seconds()
            ) shouldBeEqualTo AcquireResult.Replay(201, "{\"holdId\":\"h-1\"}", failed = false)
        }
    }

    @Test
    fun `same scoped key with a different payload is rejected deterministically`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            repository.acquire(
                scope = scope,
                requestFingerprint = fingerprint,
                ownerToken = UUID.randomUUID(),
                now = now
            )

            repository.acquire(
                scope = scope,
                requestFingerprint = IdempotencyFingerprint.request("create-hold", "resource=room-b"),
                ownerToken = UUID.randomUUID(),
                now = now + 1.seconds()
            ) shouldBeEqualTo AcquireResult.FingerprintConflict
        }
    }

    @Test
    fun `active owner returns the remaining lease as in progress`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            repository.acquire(
                scope = scope,
                requestFingerprint = fingerprint,
                ownerToken = UUID.randomUUID(),
                now = now
            )

            repository.acquire(
                scope = scope,
                requestFingerprint = fingerprint,
                ownerToken = UUID.randomUUID(),
                now = now + 30.seconds()
            ) shouldBeEqualTo AcquireResult.InProgress(60.seconds())
        }
    }

    @Test
    fun `expired owner is taken over and stale finalize compare and set is rejected`() {
        withTables(TestDB.POSTGRESQL, *reservationTables) {
            val staleOwner = UUID.randomUUID()
            val original = repository.acquire(
                scope = scope,
                requestFingerprint = fingerprint,
                ownerToken = staleOwner,
                now = now
            ).shouldBeInstanceOf<AcquireResult.New>()

            val replacementOwner = UUID.randomUUID()

            val takeover = repository.acquire(
                scope = scope,
                requestFingerprint = fingerprint,
                ownerToken = replacementOwner,
                now = now + 91.seconds()
            ) as AcquireResult.Takeover

            takeover.record.ownerToken shouldBeEqualTo replacementOwner

            repository.finalize(
                id = original.record.id,
                ownerToken = staleOwner,
                responseStatus = 201,
                responseBody = "stale",
                failed = false
            ).shouldBeFalse()

            repository.finalize(
                id = original.record.id,
                ownerToken = replacementOwner,
                responseStatus = 201,
                responseBody = "fresh",
                failed = false
            ).shouldBeTrue()
        }
    }
}
