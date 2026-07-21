package io.bluetape4k.workshop.commerce.ticket.purchase

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.admission.internal.AdmissionService
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import io.bluetape4k.workshop.commerce.ticket.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyScope
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabaseFixture
import io.bluetape4k.workshop.commerce.ticket.purchase.api.AuthorizationRequested
import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.api.StartPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseEventPublisher
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseService
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePolicySnapshot
import io.bluetape4k.workshop.commerce.ticket.salecontrol.internal.SaleNotStarted
import io.bluetape4k.workshop.commerce.ticket.salecontrol.internal.SaleService
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal class PurchaseServiceIntegrationTest {
    @Test
    fun `one nanosecond before opensAt creates no purchase row or consumed grant`() {
        PurchaseFixture(opensAt = NOW.plusNanos(1)).use { fixture ->
            val command = fixture.command()

            assertFailsWith<SaleNotStarted> { fixture.service.start(command) }

            fixture.durableState(command.attemptId) shouldBeEqualTo DurableState.ZERO
            fixture.events.size shouldBeEqualTo 0
        }
    }

    @Test
    fun `purchase atomically consumes grant holds inventory guards identities and binds idempotency`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()

            val snapshot = fixture.service.start(command)

            snapshot.state shouldBeEqualTo PurchaseState.INVENTORY_HELD
            fixture.durableState(command.attemptId) shouldBeEqualTo
                DurableState(attempts = 1, guards = 2, held = 1, consumedGrants = 1, boundIdempotency = 1)
            fixture.events.size shouldBeEqualTo 1
            fixture.events.single().attemptId shouldBeEqualTo snapshot.attemptId
            fixture.events.single().operationId shouldBeEqualTo command.authorizationOperationId
        }
    }

    @Test
    fun `cancel before authorization releases hold and both identity guards`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)

            val cancelled = fixture.service.cancel(CancelPurchase(command.attemptId, command.buyerSubjectId))

            cancelled.state shouldBeEqualTo PurchaseState.CANCELLED
            fixture.durableState(command.attemptId) shouldBeEqualTo
                DurableState(attempts = 1, guards = 0, held = 0, consumedGrants = 1, boundIdempotency = 1)
        }
    }

    @Test
    fun `cancel during authorization keeps hold and guards until provider outcome`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)
            fixture.execute(
                "UPDATE ticket_purchase_attempts SET state = 'payment_authorizing' WHERE attempt_id = '${command.attemptId}'",
            )

            val cancelling = fixture.service.cancel(CancelPurchase(command.attemptId, command.buyerSubjectId))

            cancelling.state shouldBeEqualTo PurchaseState.CANCELLATION_REQUESTED
            fixture.durableState(command.attemptId) shouldBeEqualTo
                DurableState(attempts = 1, guards = 2, held = 1, consumedGrants = 1, boundIdempotency = 1)
        }
    }
}

internal data class DurableState(
    val attempts: Int,
    val guards: Int,
    val held: Int,
    val consumedGrants: Int,
    val boundIdempotency: Int,
) {
    companion object {
        val ZERO = DurableState(0, 0, 0, 0, 0)
    }
}

internal class PurchaseFixture(
    val inventory: Int = 2,
    val opensAt: Instant = NOW.minusSeconds(1),
) : AutoCloseable {
    private val database = TicketDatabaseFixture()
    val saleId: UUID = UUID.randomUUID()
    val sharedIp: UUID = UUID.randomUUID()
    val events = mutableListOf<AuthorizationRequested>()
    val service =
        PurchaseService(
            jdbc = database.executor,
            sale = SaleService(),
            admission = AdmissionService(database.executor, Clock.fixed(NOW, ZoneOffset.UTC)),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            events = PurchaseEventPublisher(events::add),
        )

    init {
        execute(
            """
            INSERT INTO ticket_sales(sale_id, state, current_policy_version, opens_at, closes_at)
            VALUES ('$saleId', 'open', 1, '$opensAt', '${NOW.plusSeconds(3600)}');
            INSERT INTO ticket_sale_policy_versions(sale_id, policy_version, per_user_limit, max_quantity, hold_seconds)
            VALUES ('$saleId', 1, 4, 4, 30);
            INSERT INTO ticket_inventory(sale_id, grade, total_quantity) VALUES ('$saleId', 'GENERAL', $inventory);
            INSERT INTO ticket_identity_subjects(subject_id, identity_kind) VALUES ('$sharedIp', 'IP');
            """.trimIndent(),
        )
    }

    fun command(
        buyer: UUID = UUID.randomUUID(),
        ip: UUID = sharedIp,
        quantity: Int = 1,
    ): StartPurchase {
        val attemptId = UUID.randomUUID()
        val grantNonce = UUID.randomUUID()
        execute(
            """
            INSERT INTO ticket_identity_subjects(subject_id, identity_kind)
            VALUES ('$buyer', 'USER') ON CONFLICT DO NOTHING;
            INSERT INTO ticket_identity_subjects(subject_id, identity_kind)
            VALUES ('$ip', 'IP') ON CONFLICT DO NOTHING;
            INSERT INTO ticket_admission_grants(sale_id, grant_nonce, buyer_subject_id, policy_version, expires_at)
            VALUES ('$saleId', '$grantNonce', '$buyer', 1, '${NOW.plusSeconds(60)}');
            """.trimIndent(),
        )
        val idempotency = HttpIdempotencyRepository(database.executor)
        val owner =
            idempotency.acquire(
                IdempotencyScope(
                    principalSubjectId = buyer,
                    httpMethod = "POST",
                    canonicalRoute = "/api/v1/sales/{saleId}/purchase-attempts",
                    resourceId = saleId.toString(),
                    operation = "purchase",
                    keyDigest = IdempotencyFingerprint.key(ByteArray(32) { 0x33 }, UUID.randomUUID().toString()),
                ),
                IdempotencyFingerprint.request("POST", "/purchase", "{\"grade\":\"GENERAL\",\"quantity\":$quantity}"),
                NOW,
            ) as IdempotencyDecision.Owner
        return StartPurchase(
            attemptId = attemptId,
            authorizationOperationId = UUID.randomUUID(),
            idempotencyOwnerId = owner.id,
            buyerSubjectId = buyer,
            ipSubjectId = ip,
            grade = "GENERAL",
            quantity = quantity,
            grant = ConsumeGrant(saleId, grantNonce, buyer, 1, attemptId),
            policy = SalePolicySnapshot(saleId, SaleState.OPEN, 1, opensAt, NOW.plusSeconds(3600)),
        )
    }

    fun durableState(attemptId: UUID): DurableState =
        database.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                  (SELECT COUNT(*) FROM ticket_purchase_attempts WHERE attempt_id = ?) AS attempts,
                  (SELECT COUNT(*) FROM ticket_active_identity_guards WHERE active_attempt_id = ?) AS guards,
                  (SELECT held_quantity FROM ticket_inventory WHERE sale_id = ? AND grade = 'GENERAL') AS held,
                  (SELECT COUNT(*) FROM ticket_admission_grants WHERE consumed_attempt_id = ?) AS consumed_grants,
                  (SELECT COUNT(*) FROM ticket_http_idempotency WHERE attempt_id = ?) AS bound_idempotency
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, attemptId)
                statement.setObject(2, attemptId)
                statement.setObject(3, saleId)
                statement.setObject(4, attemptId)
                statement.setObject(5, attemptId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    DurableState(
                        attempts = result.getInt("attempts"),
                        guards = result.getInt("guards"),
                        held = result.getInt("held"),
                        consumedGrants = result.getInt("consumed_grants"),
                        boundIdempotency = result.getInt("bound_idempotency"),
                    )
                }
            }
        }

    fun execute(sql: String) = database.execute(sql)

    fun queryInt(sql: String): Int =
        database.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }

    override fun close() = database.close()
}

internal val NOW: Instant = Instant.parse("2026-07-21T10:00:00Z")
