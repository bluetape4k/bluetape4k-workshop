package io.bluetape4k.workshop.commerce.voucher.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class HttpIdempotencyRepositoryTest {
    private val gate =
        DatabasePermitGate(
            foregroundPermits = 1,
            workerPermits = 1,
            sseMaintenancePermits = 1,
            acquireTimeout = Duration.ofSeconds(1),
        )
    private val repository = HttpIdempotencyRepository(gate)

    @Test
    fun `new owner finalizes a closed response and terminal replay is deterministic`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val owner = withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW) } as IdempotencyAcquireResult.Owner

            withPermit { repository.finalize(SCOPE, owner.ownerToken, NOW.plusSeconds(1), RESPONSE) }.shouldBeTrue()
            withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW.plusSeconds(2)) } shouldBeEqualTo
                IdempotencyAcquireResult.Replay(RESPONSE)
        }
    }

    @Test
    fun `same scoped key with a semantic payload difference conflicts`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW) }

            withPermit { repository.acquire(SCOPE, Digest.sha256("different"), NOW.plusSeconds(1)) } shouldBeEqualTo
                IdempotencyAcquireResult.FingerprintConflict
        }
    }

    @Test
    fun `active lease reports bounded retry and expired lease rejects stale finalize`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val first = withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW) } as IdempotencyAcquireResult.Owner
            withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW.plusSeconds(30)) } shouldBeEqualTo
                IdempotencyAcquireResult.InProgress(Duration.ofSeconds(60))

            val second =
                withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW.plusSeconds(91)) }
                    as IdempotencyAcquireResult.Owner

            withPermit { repository.finalize(SCOPE, first.ownerToken, NOW.plusSeconds(92), RESPONSE) }.shouldBeFalse()
            withPermit { repository.finalize(SCOPE, second.ownerToken, NOW.plusSeconds(92), RESPONSE) }.shouldBeTrue()
        }
    }

    @Test
    fun `command deadline expires before lease and prevents a late business finalize`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val owner = withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW) } as IdempotencyAcquireResult.Owner

            withPermit { repository.isOwner(SCOPE, owner.ownerToken, NOW.plusSeconds(61)) }.shouldBeFalse()
            withPermit { repository.finalize(SCOPE, owner.ownerToken, NOW.plusSeconds(61), RESPONSE) }.shouldBeFalse()
            withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW.plusSeconds(61)) } shouldBeEqualTo
                IdempotencyAcquireResult.InProgress(Duration.ofSeconds(29))
        }
    }

    @Test
    fun `retryable outcome releases owner so the same key can be reacquired`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val first = withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW) } as IdempotencyAcquireResult.Owner

            withPermit { repository.release(SCOPE, first.ownerToken) }.shouldBeTrue()
            withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW.plusSeconds(1)) }
                .let { it is IdempotencyAcquireResult.Owner }
                .shouldBeTrue()
        }
    }

    @Test
    fun `only expired terminal rows are cleaned in a bounded batch`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val expiredScope = SCOPE.copy(resourceId = "expired", keyDigest = Digest.sha256("expired"))
            val activeScope = SCOPE.copy(resourceId = "active", keyDigest = Digest.sha256("active"))
            val expired =
                withPermit {
                    repository.acquire(expiredScope, FINGERPRINT, NOW, retention = Duration.ofSeconds(120))
                } as IdempotencyAcquireResult.Owner
            val active = withPermit { repository.acquire(activeScope, FINGERPRINT, NOW) } as IdempotencyAcquireResult.Owner
            withPermit { repository.finalize(expiredScope, expired.ownerToken, NOW.plusSeconds(1), RESPONSE) }
            withPermit { repository.finalize(activeScope, active.ownerToken, NOW.plusSeconds(1), RESPONSE) }

            withPermit { repository.cleanupExpired(NOW.plusSeconds(121), limit = 1) } shouldBeEqualTo 1
            withPermit { repository.find(expiredScope) }.shouldBeNull()
            withPermit { repository.find(activeScope) }?.response shouldBeEqualTo RESPONSE
        }
    }

    @Test
    fun `storage excludes raw idempotency keys arbitrary json and voucher codes`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val owner = withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW) } as IdempotencyAcquireResult.Owner
            withPermit { repository.finalize(SCOPE, owner.ownerToken, NOW.plusSeconds(1), RESPONSE) }

            val columns = HttpIdempotencyTable.columns.map { it.name }.toSet()
            columns.any { it in setOf("raw_key", "response_body", "voucher_code", "owner_token") }.shouldBeFalse()
            withPermit { HttpIdempotencyTable.selectAll().single()[HttpIdempotencyTable.responseHeaders] } shouldBeEqualTo
                "ETag=%22claim-3%22&Location=%2Fclaims%2Fclaim-1"
        }
    }

    private fun <T> withPermit(block: () -> T): T = gate.withPermit(DatabaseLane.FOREGROUND, block)

    companion object {
        private val NOW = Instant.parse("2026-07-19T10:00:00Z")
        private val SCOPE =
            IdempotencyScope(
                tenantId = "tenant-a",
                principalDigest = Digest.sha256("principal-a"),
                operation = "ALLOCATE",
                resourceId = "campaign-1",
                keyDigest = Digest.sha256("raw-idempotency-key"),
            )
        private val FINGERPRINT = Digest.sha256("canonical-request")
        private val RESPONSE =
            StoredHttpResponse(
                responseKind = VoucherResponseKind.ALLOCATION_ACCEPTED,
                status = 201,
                headers = mapOf("Location" to "/claims/claim-1", "ETag" to "\"claim-3\""),
                aggregateId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890bc"),
                allocationId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890cd"),
                aggregateRevision = 3,
                generationKeyVersion = 5,
                verificationKeyVersion = 7,
            )
    }
}
