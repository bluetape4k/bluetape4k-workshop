package io.bluetape4k.workshop.commerce.ticket.purchase

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.admission.internal.AdmissionService
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import io.bluetape4k.workshop.commerce.ticket.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyScope
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketActiveIdentityGuards
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketAdmissionGrants
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabaseFixture
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectOperations
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectReceipts
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketHttpIdempotencies
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketInventories
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketOrders
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPaymentOperations
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPurchaseAttempts
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketTickets
import io.bluetape4k.workshop.commerce.ticket.purchase.api.AuthorizationRequested
import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.api.StartPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseEventPublisher
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseService
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePolicySnapshot
import io.bluetape4k.workshop.commerce.ticket.salecontrol.internal.SaleNotStarted
import io.bluetape4k.workshop.commerce.ticket.salecontrol.internal.SaleService
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

internal class PurchaseServiceIntegrationTest {

    companion object: KLogging()

    @Test
    fun `integration fixture uses a bounded Hikari pool`() {
        TicketDatabaseFixture().use { fixture ->
            fixture.dataSource.maximumPoolSize shouldBeEqualTo TicketDatabaseFixture.MAXIMUM_POOL_SIZE
        }
    }

    @Test
    fun `one nanosecond before opensAt creates no purchase row or consumed grant`() {
        PurchaseFixture(opensAt = NOW.plusNanos(1)).use { fixture ->
            val command = fixture.command()

            assertFailsWith<SaleNotStarted> { fixture.service.start(command) }

            fixture.durableState(command.attemptId) shouldBeEqualTo DurableState.ZERO
            fixture.events.shouldBeEmpty()
        }
    }

    @Test
    fun `purchase atomically consumes grant holds inventory guards identities and binds idempotency`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()

            val snapshot = fixture.service.start(command)
            log.debug { "snapshot: $snapshot" }

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

            log.debug { "cancelled: $cancelled" }
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

            log.debug { "cancelling: $cancelling" }
            cancelling.state shouldBeEqualTo PurchaseState.CANCELLATION_REQUESTED
            fixture.durableState(command.attemptId) shouldBeEqualTo
                    DurableState(attempts = 1, guards = 2, held = 1, consumedGrants = 1, boundIdempotency = 1)
        }
    }

    @Test
    fun `owner query hides an attempt from another buyer`() {
        PurchaseFixture().use { fixture ->
            val command = fixture.command()
            fixture.service.start(command)

            fixture.service.owned(
                command.attemptId,
                command.buyerSubjectId
            )?.attemptId shouldBeEqualTo command.attemptId

            fixture.service.owned(command.attemptId, Uuid.V7.nextId()).shouldBeNull()
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

internal data class InventoryQuantities(
    val total: Int,
    val held: Int,
    val sold: Int,
)

@Suppress("SqlResolve", "SqlSourceToSinkFlow")
internal class PurchaseFixture(
    val inventory: Int = 2,
    val opensAt: Instant = NOW - 1.seconds(),
): AutoCloseable {

    companion object: KLogging()

    private val database = TicketDatabaseFixture()
    val executor get() = database.executor
    val saleId: UUID = Uuid.V7.nextId()
    val sharedIp: UUID = Uuid.V7.nextId()
    val events = mutableListOf<AuthorizationRequested>()

    val service = PurchaseService(
        jdbcExecutor = database.executor,
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
        buyer: UUID = Uuid.V7.nextId(),
        ip: UUID = sharedIp,
        quantity: Int = 1,
    ): StartPurchase {
        val attemptId = Uuid.V7.nextId()
        // grantNonce는 승인 토큰의 보안 nonce fallback이므로 영속 식별자 생성 계약에서 제외한다.
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
        val owner = idempotency.acquire(
            IdempotencyScope(
                principalSubjectId = buyer,
                httpMethod = "POST",
                canonicalRoute = "/api/v1/sales/{saleId}/purchase-attempts",
                resourceId = saleId.toString(),
                operation = "purchase",
                keyDigest = IdempotencyFingerprint.key(ByteArray(32) { 0x33 }, Uuid.V7.nextId().toString()),
            ),
            IdempotencyFingerprint.request("POST", "/purchase", "{\"grade\":\"GENERAL\",\"quantity\":$quantity}"),
            NOW,
        ).shouldBeInstanceOf<IdempotencyDecision.Owner>()

        return StartPurchase(
            attemptId = attemptId,
            authorizationOperationId = Uuid.V7.nextId(),
            idempotencyOwnerId = owner.id,
            buyerSubjectId = buyer,
            ipSubjectId = ip,
            grade = "GENERAL",
            quantity = quantity,
            grant = ConsumeGrant(saleId, grantNonce, buyer, 1, attemptId),
            policy = SalePolicySnapshot(saleId, SaleState.OPEN, 1, opensAt, NOW + 3600.seconds()),
        )
    }

    fun durableState(attemptId: UUID): DurableState = executor.transaction {
        DurableState(
            attempts = TicketPurchaseAttempts.selectAll()
                .where { TicketPurchaseAttempts.id eq attemptId }
                .count()
                .toInt(),
            guards = TicketActiveIdentityGuards.selectAll()
                .where { TicketActiveIdentityGuards.activeAttemptId eq attemptId }
                .count()
                .toInt(),
            held = inventoryQuantitiesInCurrentTransaction().held,
            consumedGrants = TicketAdmissionGrants.selectAll()
                .where { TicketAdmissionGrants.consumedAttemptId eq attemptId }
                .count()
                .toInt(),
            boundIdempotency = TicketHttpIdempotencies.selectAll()
                .where { TicketHttpIdempotencies.attemptId eq attemptId }
                .count()
                .toInt(),
        )
    }

    fun execute(sql: String) = database.execute(sql)

    fun inventoryQuantities(): InventoryQuantities = executor.transaction {
        inventoryQuantitiesInCurrentTransaction()
    }

    private fun inventoryQuantitiesInCurrentTransaction(): InventoryQuantities {
        val row = TicketInventories
            .select(
                TicketInventories.totalQuantity,
                TicketInventories.heldQuantity,
                TicketInventories.soldQuantity,
            )
            .where {
                (TicketInventories.saleId eq this@PurchaseFixture.saleId) and
                        (TicketInventories.grade eq "GENERAL")
            }
            .single()
        return InventoryQuantities(
            total = row[TicketInventories.totalQuantity],
            held = row[TicketInventories.heldQuantity],
            sold = row[TicketInventories.soldQuantity],
        )
    }

    fun inventoryHeld(): Int = inventoryQuantities().held

    fun inventorySold(): Int = inventoryQuantities().sold

    fun purchaseAttemptState(attemptId: UUID): String = executor.transaction {
        TicketPurchaseAttempts
            .select(TicketPurchaseAttempts.state)
            .where { TicketPurchaseAttempts.id eq attemptId }
            .single()[TicketPurchaseAttempts.state]
    }

    fun ticketState(): String = executor.transaction {
        TicketTickets.select(TicketTickets.state).single()[TicketTickets.state]
    }

    fun ticketDisposition(): String = executor.transaction {
        TicketOrders.select(TicketOrders.ticketDisposition).single()[TicketOrders.ticketDisposition]
    }

    fun issueEffectOperationId(): UUID = executor.transaction {
        TicketEffectOperations
            .select(TicketEffectOperations.operationId)
            .where { TicketEffectOperations.effectKind eq "issue" }
            .single()[TicketEffectOperations.operationId]
    }

    fun refundOperationId(attemptId: UUID): UUID = executor.transaction {
        checkNotNull(
            TicketOrders
                .select(TicketOrders.refundOperationId)
                .where { TicketOrders.attemptId eq attemptId }
                .single()[TicketOrders.refundOperationId],
        )
    }

    fun effectReceiptCount(operationId: UUID): Int = executor.transaction {
        TicketEffectReceipts
            .selectAll()
            .where { TicketEffectReceipts.operationId eq operationId }
            .count()
            .toInt()
    }

    fun ticketCount(): Int = executor.transaction { TicketTickets.selectAll().count().toInt() }

    fun effectOperationCount(): Int = executor.transaction { TicketEffectOperations.selectAll().count().toInt() }

    fun orderCount(attemptId: UUID): Int = executor.transaction {
        TicketOrders.selectAll().where { TicketOrders.attemptId eq attemptId }.count().toInt()
    }

    fun identityGuardCount(kind: String, subjectId: UUID): Int = executor.transaction {
        TicketActiveIdentityGuards
            .selectAll()
            .where {
                (TicketActiveIdentityGuards.saleId eq this@PurchaseFixture.saleId) and
                        (TicketActiveIdentityGuards.identityKind eq kind) and
                        (TicketActiveIdentityGuards.identitySubjectId eq subjectId)
            }
            .count()
            .toInt()
    }

    fun activeIdentityGuardCount(attemptId: UUID): Int = executor.transaction {
        TicketActiveIdentityGuards
            .selectAll()
            .where { TicketActiveIdentityGuards.activeAttemptId eq attemptId }
            .count()
            .toInt()
    }

    fun duplicatePaymentOperationCount(): Int = executor.transaction {
        TicketPaymentOperations
            .select(TicketPaymentOperations.operationId)
            .map { it[TicketPaymentOperations.operationId] }
            .groupingBy { it }
            .eachCount()
            .count { (_, count) -> count > 1 }
    }

    fun duplicateEffectReceiptCount(): Int = executor.transaction {
        TicketEffectReceipts
            .select(TicketEffectReceipts.operationId)
            .map { it[TicketEffectReceipts.operationId] }
            .groupingBy { it }
            .eachCount()
            .count { (_, count) -> count > 1 }
    }

    fun expireEffectClaim(operationId: UUID) {
        // 만료 주입은 애플리케이션 clock이 아니라 PostgreSQL statement clock을 검증한다.
        execute(
            "UPDATE ticket_effect_operations SET claim_until = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                    "WHERE operation_id = '$operationId'",
        )
    }

    override fun close() {
        database.close()
    }
}

internal val NOW: Instant = Instant.parse("2026-07-21T10:00:00Z")
