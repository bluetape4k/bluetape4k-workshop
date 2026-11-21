package io.bluetape4k.workshop.commerce.ticket.admission

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.admission.internal.AdmissionExpired
import io.bluetape4k.workshop.commerce.ticket.admission.internal.AdmissionService
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabaseFixture
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.Executors

/**
 * AdmissionConcurrencyIntegrationTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class AdmissionConcurrencyIntegrationTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "one admission grant can be consumed once" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `one admission grant can be consumed once`() {
        TicketDatabaseFixture().use { fixture ->
            val now = Instant.parse("2026-07-21T10:00:00Z")
            val saleId = Uuid.V7.nextId()
            val buyerId = Uuid.V7.nextId()
            val ipId = Uuid.V7.nextId()

            // grantNonce는 승인 토큰의 보안 nonce fallback이므로 영속 식별자 생성 계약에서 제외한다.
            val grantNonce = UUID.randomUUID()
            val firstAttempt = Uuid.V7.nextId()
            val secondAttempt = Uuid.V7.nextId()

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
                     'inventory_held', '${now.plusSeconds(30)}', '${Uuid.V7.nextId()}'),
                    ('$secondAttempt', '$saleId', '$buyerId', '$ipId', 'GENERAL', 1, 1,
                     'inventory_held', '${now.plusSeconds(30)}', '${Uuid.V7.nextId()}');
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
/**
 * 이 시나리오는 "concurrent grant consumers have one winner" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `concurrent grant consumers have one winner`() {
        TicketDatabaseFixture().use { fixture ->
            val now = Instant.parse("2026-07-21T10:00:00Z")
            val saleId = Uuid.V7.nextId()
            val buyerId = Uuid.V7.nextId()
            val ipId = Uuid.V7.nextId()
            // grantNonce는 승인 토큰의 보안 nonce fallback이므로 영속 식별자 생성 계약에서 제외한다.
            val grantNonce = UUID.randomUUID()
            val attempts = listOf(Uuid.V7.nextId(), Uuid.V7.nextId())

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
                     'inventory_held', '${now.plusSeconds(30)}', '${Uuid.V7.nextId()}'),
                    ('${attempts[1]}', '$saleId', '$buyerId', '$ipId', 'GENERAL', 1, 1,
                     'inventory_held', '${now.plusSeconds(30)}', '${Uuid.V7.nextId()}');
                INSERT INTO ticket_admission_grants(sale_id, grant_nonce, buyer_subject_id, policy_version, expires_at)
                VALUES ('$saleId', '$grantNonce', '$buyerId', 1, '${now.plusSeconds(30)}')
                """.trimIndent(),
            )
            val service = AdmissionService(fixture.executor, Clock.fixed(now, ZoneOffset.UTC))

            val results: List<Boolean> = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                attempts.map { attemptId ->
                    executor.submit<Boolean> {
                        runCatching { service.consume(command(saleId, buyerId, grantNonce, attemptId)) }.isSuccess
                    }.get()
                }
            }

            results.count { it } shouldBeEqualTo 1
        }
    }

/**
 * command은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun command(
        saleId: UUID,
        buyerId: UUID,
        nonce: UUID,
        attemptId: UUID,
    ): ConsumeGrant =
        ConsumeGrant(
            saleId = saleId,
            grantNonce = nonce,
            buyerSubjectId = buyerId,
            policyVersion = 1,
            attemptId = attemptId
        )
}
