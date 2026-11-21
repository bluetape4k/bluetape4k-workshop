package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * TicketRepositoryIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketRepositoryIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "inventory check rejects held plus sold above total" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "user and ip active guards are unique per sale" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "waiting room claim uses canonical fifo index" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
                .onEach { log.debug { "claimed=$it" } }
            claimed.map { it.sequence } shouldBeEqualTo listOf(93L, 94L, 95L)
            repository.explainClaim(saleId).any { it.contains("ticket_waiting_claim_idx") }.shouldBeTrue()
        }
    }

    @Test
/**
 * 이 시나리오는 "global lock order rejects reverse acquisition" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "payment operation is unique and reconciliation uses its due index" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
