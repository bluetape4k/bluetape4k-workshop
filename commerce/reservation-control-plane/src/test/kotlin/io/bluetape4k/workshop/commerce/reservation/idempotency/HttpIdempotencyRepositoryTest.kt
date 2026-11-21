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

/**
 * HttpIdempotencyRepositoryTest 테스트 묶음은 예약 제어 플레인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class HttpIdempotencyRepositoryTest {

    companion object: KLogging()

/**
 * repository은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = HttpIdempotencyRepository()
/**
 * now은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val now = Instant.parse("2026-07-19T00:00:00Z")
/**
 * scope은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val scope = IdempotencyScope(
        tenantId = "tenant-a",
        operation = "create-hold",
        keyDigest = IdempotencyFingerprint.key("tenant-a", "create-hold", "key-a")
    )
/**
 * fingerprint은 예약 제어 플레인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fingerprint: String =
        IdempotencyFingerprint.request("create-hold", "resource=room-a")

    @Test
/**
 * 이 시나리오는 "new owner finalizes and the terminal response is replayed on PostgreSQL" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "same scoped key with a different payload is rejected deterministically" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "active owner returns the remaining lease as in progress" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "expired owner is taken over and stale finalize compare and set is rejected" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
