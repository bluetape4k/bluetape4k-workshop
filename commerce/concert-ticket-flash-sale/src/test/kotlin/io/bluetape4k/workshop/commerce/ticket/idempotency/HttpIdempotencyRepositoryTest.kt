package io.bluetape4k.workshop.commerce.ticket.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.ticket.persistence.IdentityKind
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketDatabaseFixture
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * HttpIdempotencyRepositoryTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class HttpIdempotencyRepositoryTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "nonterminal replay returns its attempt before any Redis decision" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `nonterminal replay returns its attempt before any Redis decision`() {
        TicketDatabaseFixture().use { fixture ->
            val principal = Uuid.V7.nextId()
            val attemptId = Uuid.V7.nextId()

            fixture.seedAuthority(
                saleId = Uuid.V7.nextId(),
                userSubjectId = principal,
                ipSubjectId = Uuid.V7.nextId(),
                firstAttemptId = attemptId,
                secondAttemptId = Uuid.V7.nextId(),
            )

            val repository = HttpIdempotencyRepository(fixture.executor)
            val scope = scope(principal, rawKey = "same-key-1234567")
            val fingerprint = IdempotencyFingerprint.request(
                method = "POST",
                path = "/purchase",
                body = "{\"quantity\":1,\"grade\":\"GENERAL\"}"
            )
            log.debug { "fingerprint=$fingerprint" }

            val owner = repository.acquire(
                scope,
                fingerprint,
                Instant.parse("2026-07-21T10:00:00Z")
            )
            owner.shouldBeInstanceOf<IdempotencyDecision.Owner>()
            repository.attachAttempt(owner.id, attemptId)

            repository.acquire(
                scope = scope,
                fingerprint = fingerprint,
                now = Instant.parse("2026-07-21T10:00:01Z")
            ) shouldBeEqualTo IdempotencyDecision.Replay(attemptId, completed = false)
        }
    }

    @Test
/**
 * 이 시나리오는 "same key cannot replay across principals and changed payload conflicts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same key cannot replay across principals and changed payload conflicts`() {
        TicketDatabaseFixture().use { fixture ->
            val firstPrincipal = Uuid.V7.nextId()
            val secondPrincipal = Uuid.V7.nextId()
            fixture.execute(
                """
                INSERT INTO ticket_identity_subjects(subject_id, identity_kind) VALUES
                    ('$firstPrincipal', '${IdentityKind.USER}'), ('$secondPrincipal', '${IdentityKind.USER}')
                """.trimIndent(),
            )
            val repository = HttpIdempotencyRepository(fixture.executor)
            val firstScope = scope(firstPrincipal, "same-key-1234567")
            val secondScope = scope(secondPrincipal, "same-key-1234567")

            val first = IdempotencyFingerprint.request(
                method = "POST",
                path = "/purchase",
                body = "{\"grade\":\"GENERAL\",\"quantity\":1}"
            )
            val reordered = IdempotencyFingerprint.request(
                method = "POST",
                path = "/purchase",
                body = "{ \"quantity\": 1, \"grade\": \"GENERAL\" }"
            )
            val changed = IdempotencyFingerprint.request(
                method = "POST",
                path = "/purchase",
                body = "{\"grade\":\"GENERAL\",\"quantity\":2}"
            )

            first shouldBeEqualTo reordered

            repository.acquire(
                scope = firstScope,
                fingerprint = first,
                now = Instant.parse("2026-07-21T10:00:00Z")
            ).shouldBeInstanceOf<IdempotencyDecision.Owner>()

            repository.acquire(
                scope = firstScope,
                fingerprint = changed,
                now = Instant.parse("2026-07-21T10:00:01Z")
            ) shouldBeEqualTo IdempotencyDecision.Conflict

            repository.acquire(
                scope = secondScope,
                fingerprint = first,
                now = Instant.parse("2026-07-21T10:00:01Z")
            ).shouldBeInstanceOf<IdempotencyDecision.Owner>()
        }
    }

/**
 * scope은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
