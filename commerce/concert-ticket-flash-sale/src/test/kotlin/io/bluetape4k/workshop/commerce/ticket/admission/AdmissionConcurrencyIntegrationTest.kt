package io.bluetape4k.workshop.commerce.ticket.admission

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.admission.internal.AdmissionExpired
import io.bluetape4k.workshop.commerce.ticket.admission.internal.AdmissionService
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabaseFixture
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors

internal class AdmissionConcurrencyIntegrationTest {
    @Test
    fun `one admission grant can be consumed once`() {
        TicketDatabaseFixture().use { fixture ->
            val now = Instant.parse("2026-07-21T10:00:00Z")
            val saleId = UUID.randomUUID()
            val buyerId = UUID.randomUUID()
            val ipId = UUID.randomUUID()
            val grantNonce = UUID.randomUUID()
            val firstAttempt = UUID.randomUUID()
            val secondAttempt = UUID.randomUUID()
            fixture.execute(
                """
                INSERT INTO ticket_sales(sale_id, state, current_policy_version, opens_at, closes_at)
                VALUES ('$saleId', 'open', 1, CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP + INTERVAL '1 hour');
                INSERT INTO ticket_sale_policy_versions(sale_id, policy_version, per_user_limit, max_quantity, hold_seconds)
                VALUES ('$saleId', 1, 1, 4, 30);
                INSERT INTO ticket_inventory(sale_id, grade, total_quantity) VALUES ('$saleId', 'GENERAL', 2);
                INSERT INTO ticket_identity_subjects(subject_id, identity_kind) VALUES
                    ('$buyerId', '${IdentityKind.USER}'), ('$ipId', '${IdentityKind.IP}');
                INSERT INTO ticket_purchase_attempts(
                    attempt_id, sale_id, user_subject_id, ip_subject_id, grade, quantity, policy_version,
                    state, hold_deadline, authorization_operation_id
                ) VALUES
                    ('$firstAttempt', '$saleId', '$buyerId', '$ipId', 'GENERAL', 1, 1,
                     'inventory_held', '${now.plusSeconds(30)}', '${UUID.randomUUID()}'),
                    ('$secondAttempt', '$saleId', '$buyerId', '$ipId', 'GENERAL', 1, 1,
                     'inventory_held', '${now.plusSeconds(30)}', '${UUID.randomUUID()}');
                INSERT INTO ticket_admission_grants(sale_id, grant_nonce, buyer_subject_id, policy_version, expires_at)
                VALUES ('$saleId', '$grantNonce', '$buyerId', 1, '${now.plusSeconds(30)}')
                """.trimIndent(),
            )
            val service = AdmissionService(fixture.executor, Clock.fixed(now, ZoneOffset.UTC))

            service.consume(command(saleId, buyerId, grantNonce, firstAttempt))
            assertFailsWith<AdmissionExpired> {
                service.consume(command(saleId, buyerId, grantNonce, secondAttempt))
            }
        }
    }

    @Test
    fun `concurrent grant consumers have one winner`() {
        TicketDatabaseFixture().use { fixture ->
            val now = Instant.parse("2026-07-21T10:00:00Z")
            val saleId = UUID.randomUUID()
            val buyerId = UUID.randomUUID()
            val ipId = UUID.randomUUID()
            val grantNonce = UUID.randomUUID()
            val attempts = listOf(UUID.randomUUID(), UUID.randomUUID())
            fixture.execute(
                """
                INSERT INTO ticket_sales(sale_id, state, current_policy_version, opens_at, closes_at)
                VALUES ('$saleId', 'open', 1, CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP + INTERVAL '1 hour');
                INSERT INTO ticket_sale_policy_versions(sale_id, policy_version, per_user_limit, max_quantity, hold_seconds)
                VALUES ('$saleId', 1, 1, 4, 30);
                INSERT INTO ticket_inventory(sale_id, grade, total_quantity) VALUES ('$saleId', 'GENERAL', 2);
                INSERT INTO ticket_identity_subjects(subject_id, identity_kind) VALUES
                    ('$buyerId', '${IdentityKind.USER}'), ('$ipId', '${IdentityKind.IP}');
                INSERT INTO ticket_purchase_attempts(
                    attempt_id, sale_id, user_subject_id, ip_subject_id, grade, quantity, policy_version,
                    state, hold_deadline, authorization_operation_id
                ) VALUES
                    ('${attempts[0]}', '$saleId', '$buyerId', '$ipId', 'GENERAL', 1, 1,
                     'inventory_held', '${now.plusSeconds(30)}', '${UUID.randomUUID()}'),
                    ('${attempts[1]}', '$saleId', '$buyerId', '$ipId', 'GENERAL', 1, 1,
                     'inventory_held', '${now.plusSeconds(30)}', '${UUID.randomUUID()}');
                INSERT INTO ticket_admission_grants(sale_id, grant_nonce, buyer_subject_id, policy_version, expires_at)
                VALUES ('$saleId', '$grantNonce', '$buyerId', 1, '${now.plusSeconds(30)}')
                """.trimIndent(),
            )
            val service = AdmissionService(fixture.executor, Clock.fixed(now, ZoneOffset.UTC))

            val results =
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    attempts.map { attemptId ->
                        executor.submit<Boolean> {
                            runCatching {
                                service.consume(command(saleId, buyerId, grantNonce, attemptId))
                            }.isSuccess
                        }
                    }.map { it.get() }
                }

            results.count { it } shouldBeEqualTo 1
        }
    }

    private fun command(
        saleId: UUID,
        buyerId: UUID,
        nonce: UUID,
        attemptId: UUID,
    ) = ConsumeGrant(saleId, nonce, buyerId, policyVersion = 1, attemptId = attemptId)
}
