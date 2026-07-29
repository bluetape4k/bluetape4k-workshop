package io.bluetape4k.workshop.commerce.ticket.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabaseFixture
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class HttpIdempotencyRepositoryTest {
    @Test
    fun `nonterminal replay returns its attempt before any Redis decision`() {
        TicketDatabaseFixture().use { fixture ->
            val principal = UUID.randomUUID()
            val attemptId = UUID.randomUUID()
            fixture.seedAuthority(
                saleId = UUID.randomUUID(),
                userSubjectId = principal,
                ipSubjectId = UUID.randomUUID(),
                firstAttemptId = attemptId,
                secondAttemptId = UUID.randomUUID(),
            )
            val repository = HttpIdempotencyRepository(fixture.executor)
            val scope = scope(principal, rawKey = "same-key-1234567")
            val fingerprint = IdempotencyFingerprint.request("POST", "/purchase", "{\"quantity\":1,\"grade\":\"GENERAL\"}")

            val owner = repository.acquire(scope, fingerprint, Instant.parse("2026-07-21T10:00:00Z")) as IdempotencyDecision.Owner
            repository.attachAttempt(owner.id, attemptId)

            repository.acquire(scope, fingerprint, Instant.parse("2026-07-21T10:00:01Z")) shouldBeEqualTo
                IdempotencyDecision.Replay(attemptId, completed = false)
        }
    }

    @Test
    fun `same key cannot replay across principals and changed payload conflicts`() {
        TicketDatabaseFixture().use { fixture ->
            val firstPrincipal = UUID.randomUUID()
            val secondPrincipal = UUID.randomUUID()
            fixture.execute(
                """
                INSERT INTO ticket_identity_subjects(subject_id, identity_kind) VALUES
                    ('$firstPrincipal', '${IdentityKind.USER}'), ('$secondPrincipal', '${IdentityKind.USER}')
                """.trimIndent(),
            )
            val repository = HttpIdempotencyRepository(fixture.executor)
            val firstScope = scope(firstPrincipal, "same-key-1234567")
            val secondScope = scope(secondPrincipal, "same-key-1234567")
            val first = IdempotencyFingerprint.request("POST", "/purchase", "{\"grade\":\"GENERAL\",\"quantity\":1}")
            val reordered = IdempotencyFingerprint.request("POST", "/purchase", "{ \"quantity\": 1, \"grade\": \"GENERAL\" }")
            val changed = IdempotencyFingerprint.request("POST", "/purchase", "{\"grade\":\"GENERAL\",\"quantity\":2}")

            first shouldBeEqualTo reordered
            repository.acquire(firstScope, first, Instant.parse("2026-07-21T10:00:00Z"))::class shouldBeEqualTo
                IdempotencyDecision.Owner::class
            repository.acquire(firstScope, changed, Instant.parse("2026-07-21T10:00:01Z")) shouldBeEqualTo
                IdempotencyDecision.Conflict
            repository.acquire(secondScope, first, Instant.parse("2026-07-21T10:00:01Z"))::class shouldBeEqualTo
                IdempotencyDecision.Owner::class
        }
    }

    private fun scope(
        principal: UUID,
        rawKey: String,
    ): IdempotencyScope =
        IdempotencyScope(
            principalSubjectId = principal,
            httpMethod = "POST",
            canonicalRoute = "/api/v1/sales/{saleId}/purchase-attempts",
            resourceId = "sale-1",
            operation = "purchase",
            keyDigest = IdempotencyFingerprint.key(ByteArray(32) { 0x33 }, rawKey),
        )
}
