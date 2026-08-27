package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.idgenerators.uuid.Uuid
import org.junit.jupiter.api.Test
import java.sql.SQLException

internal class TicketRepositoryIntegrationTest {
    @Test
    fun `inventory check rejects held plus sold above total`() {
        TicketDatabaseFixture().use { fixture ->
            val repository = TicketInventoryRepository(fixture.executor)
            val saleId = Uuid.V7.nextId()
            fixture.seedSale(saleId, totalQuantity = 1)

            assertFailsWith<SQLException> {
                repository.forceQuantities(saleId, "GENERAL", held = 1, sold = 1)
            }
            repository.get(saleId, "GENERAL") shouldBeEqualTo InventoryRecord(saleId, "GENERAL", 1, 0, 0, 0)
        }
    }

    @Test
    fun `user and ip active guards are unique per sale`() {
        TicketDatabaseFixture().use { fixture ->
            val saleId = Uuid.V7.nextId()
            val userSubjectId = Uuid.V7.nextId()
            val ipSubjectId = Uuid.V7.nextId()
            val firstAttemptId = Uuid.V7.nextId()
            val secondAttemptId = Uuid.V7.nextId()
            fixture.seedAuthority(saleId, userSubjectId, ipSubjectId, firstAttemptId, secondAttemptId)
            val repository = TicketIdentityGuardRepository(fixture.executor)

            repository.insert(saleId, IdentityKind.USER, userSubjectId, firstAttemptId)
            repository.insert(saleId, IdentityKind.IP, ipSubjectId, firstAttemptId)
            assertFailsWith<SQLException> {
                repository.insert(saleId, IdentityKind.USER, userSubjectId, secondAttemptId)
            }
            assertFailsWith<SQLException> {
                repository.insert(saleId, IdentityKind.IP, ipSubjectId, secondAttemptId)
            }
        }
    }

    @Test
    fun `waiting room claim uses canonical fifo index`() {
        TicketDatabaseFixture().use { fixture ->
            val saleId = Uuid.V7.nextId()
            fixture.seedSale(saleId)
            val repository = TicketWaitingRoomRepository(fixture.executor)
            repeat(8) { index ->
                val subjectId = Uuid.V7.nextId()
                fixture.seedIdentitySubject(subjectId)
                repository.join(saleId, subjectId, sequence = 100L - index)
            }

            val claimed = repository.claimBatch(saleId, limit = 3)
            claimed.map { it.sequence } shouldBeEqualTo listOf(93L, 94L, 95L)
            repository.explainClaim(saleId).any { it.contains("ticket_waiting_claim_idx") }.shouldBeTrue()
        }
    }

    @Test
    fun `global lock order rejects reverse acquisition`() {
        TicketDatabaseFixture().use { fixture ->
            val failure =
                assertFailsWith<TicketLockOrderViolation> {
                    fixture.executor.transaction {
                        acquire(TicketLockRank.INVENTORY)
                        acquire(TicketLockRank.USER_GUARD)
                    }
                }

            failure.previous shouldBeEqualTo TicketLockRank.INVENTORY
            failure.requested shouldBeEqualTo TicketLockRank.USER_GUARD
        }
    }

    @Test
    fun `payment operation is unique and reconciliation uses its due index`() {
        TicketDatabaseFixture().use { fixture ->
            val saleId = Uuid.V7.nextId()
            val userSubjectId = Uuid.V7.nextId()
            val ipSubjectId = Uuid.V7.nextId()
            val attemptId = Uuid.V7.nextId()
            fixture.seedAuthority(saleId, userSubjectId, ipSubjectId, attemptId, Uuid.V7.nextId())
            val repository = TicketPaymentOperationRepository(fixture.executor)
            val operationId = Uuid.V7.nextId()

            repository.insertAuthorization("fake-pg", operationId, attemptId)
            assertFailsWith<SQLException> {
                repository.insertAuthorization("fake-pg", operationId, attemptId)
            }
            repository.explainDue().any { it.contains("ticket_reconcile_due_idx") }.shouldBeTrue()
        }
    }
}
