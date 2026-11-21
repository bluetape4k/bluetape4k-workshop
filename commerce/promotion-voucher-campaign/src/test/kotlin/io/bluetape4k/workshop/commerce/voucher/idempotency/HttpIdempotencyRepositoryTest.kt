package io.bluetape4k.workshop.commerce.voucher.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * HttpIdempotencyRepositoryTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class HttpIdempotencyRepositoryTest {

    companion object: KLogging() {
/**
 * NOW은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-19T10:00:00Z")
/**
 * SCOPE은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val SCOPE = IdempotencyScope(
            tenantId = "tenant-a",
            principalDigest = Digest.sha256("principal-a"),
            operation = "ALLOCATE",
            resourceId = "campaign-1",
            keyDigest = Digest.sha256("raw-idempotency-key"),
        )
/**
 * FINGERPRINT은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val FINGERPRINT = Digest.sha256("canonical-request")
/**
 * RESPONSE은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val RESPONSE = StoredHttpResponse(
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

/**
 * gate은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val gate = DatabasePermitGate(
        foregroundPermits = 1,
        workerPermits = 1,
        sseMaintenancePermits = 1,
        acquireTimeout = 1.seconds(),
    )
/**
 * repository은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = HttpIdempotencyRepository(gate)

    @Test
/**
 * 이 시나리오는 "new owner finalizes a closed response and terminal replay is deterministic" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `new owner finalizes a closed response and terminal replay is deterministic`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val owner = withPermit {
                repository.acquire(SCOPE, FINGERPRINT, NOW)
            }.shouldBeInstanceOf<IdempotencyAcquireResult.Owner>()

            withPermit {
                repository.finalize(SCOPE, owner.ownerToken, NOW + 1.seconds(), RESPONSE)
            }.shouldBeTrue()

            withPermit {
                repository.acquire(SCOPE, FINGERPRINT, NOW + 2.seconds())
            } shouldBeEqualTo IdempotencyAcquireResult.Replay(RESPONSE)
        }
    }

    @Test
/**
 * 이 시나리오는 "same scoped key with a semantic payload difference conflicts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same scoped key with a semantic payload difference conflicts`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            withPermit {
                repository.acquire(SCOPE, FINGERPRINT, NOW)
            }
            withPermit {
                repository.acquire(SCOPE, Digest.sha256("different"), NOW + 1.seconds())
            } shouldBeEqualTo IdempotencyAcquireResult.FingerprintConflict
        }
    }

    @Test
/**
 * 이 시나리오는 "active lease reports bounded retry and expired lease rejects stale finalize" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `active lease reports bounded retry and expired lease rejects stale finalize`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val first = withPermit {
                repository.acquire(SCOPE, FINGERPRINT, NOW)
            }.shouldBeInstanceOf<IdempotencyAcquireResult.Owner>()

            withPermit {
                repository.acquire(SCOPE, FINGERPRINT, NOW.plusSeconds(30))
            } shouldBeEqualTo IdempotencyAcquireResult.InProgress(60.seconds())

            val second = withPermit {
                repository.acquire(SCOPE, FINGERPRINT, NOW.plusSeconds(91))
            }.shouldBeInstanceOf<IdempotencyAcquireResult.Owner>()

            withPermit {
                repository.finalize(SCOPE, first.ownerToken, NOW.plusSeconds(92), RESPONSE)
            }.shouldBeFalse()

            withPermit {
                repository.finalize(SCOPE, second.ownerToken, NOW.plusSeconds(92), RESPONSE)
            }.shouldBeTrue()
        }
    }

    @Test
/**
 * 이 시나리오는 "command deadline expires before lease and prevents a late business finalize" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `command deadline expires before lease and prevents a late business finalize`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val owner = withPermit {
                repository.acquire(SCOPE, FINGERPRINT, NOW)
            }.shouldBeInstanceOf<IdempotencyAcquireResult.Owner>()

            withPermit {
                repository.isOwner(SCOPE, owner.ownerToken, NOW.plusSeconds(61))
            }.shouldBeFalse()

            withPermit {
                repository.finalize(SCOPE, owner.ownerToken, NOW.plusSeconds(61), RESPONSE)
            }.shouldBeFalse()

            withPermit {
                repository.acquire(SCOPE, FINGERPRINT, NOW.plusSeconds(61))
            } shouldBeEqualTo IdempotencyAcquireResult.InProgress(29.seconds())
        }
    }

    @Test
/**
 * 이 시나리오는 "retryable outcome releases owner so the same key can be reacquired" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retryable outcome releases owner so the same key can be reacquired`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val first = withPermit {
                repository.acquire(SCOPE, FINGERPRINT, NOW)
            }.shouldBeInstanceOf<IdempotencyAcquireResult.Owner>()

            withPermit { repository.release(SCOPE, first.ownerToken) }.shouldBeTrue()
            withPermit { repository.acquire(SCOPE, FINGERPRINT, NOW + 1.seconds()) }
                .let { it is IdempotencyAcquireResult.Owner }
                .shouldBeTrue()
        }
    }

    @Test
/**
 * 이 시나리오는 "only expired terminal rows are cleaned in a bounded batch" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `only expired terminal rows are cleaned in a bounded batch`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val expiredScope = SCOPE.copy(resourceId = "expired", keyDigest = Digest.sha256("expired"))
            val activeScope = SCOPE.copy(resourceId = "active", keyDigest = Digest.sha256("active"))
            val expired = withPermit {
                repository.acquire(expiredScope, FINGERPRINT, NOW, retention = 120.seconds())
            }.shouldBeInstanceOf<IdempotencyAcquireResult.Owner>()

            val active = withPermit {
                repository.acquire(activeScope, FINGERPRINT, NOW)
            }.shouldBeInstanceOf<IdempotencyAcquireResult.Owner>()

            withPermit { repository.finalize(expiredScope, expired.ownerToken, NOW + 1.seconds(), RESPONSE) }
            withPermit { repository.finalize(activeScope, active.ownerToken, NOW + 1.seconds(), RESPONSE) }

            withPermit { repository.cleanupExpired(NOW.plusSeconds(121), limit = 1) } shouldBeEqualTo 1
            withPermit { repository.find(expiredScope) }.shouldBeNull()
            withPermit { repository.find(activeScope) }?.response shouldBeEqualTo RESPONSE
        }
    }

    @Test
/**
 * 이 시나리오는 "storage excludes raw idempotency keys arbitrary json and voucher codes" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `storage excludes raw idempotency keys arbitrary json and voucher codes`() {
        withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
            val owner = withPermit {
                repository.acquire(SCOPE, FINGERPRINT, NOW)
            }.shouldBeInstanceOf<IdempotencyAcquireResult.Owner>()

            withPermit {
                repository.finalize(SCOPE, owner.ownerToken, NOW + 1.seconds(), RESPONSE)
            }

            val columns = HttpIdempotencyTable.columns.map { it.name }.toSet()
            columns.any { it in setOf("raw_key", "response_body", "voucher_code", "owner_token") }.shouldBeFalse()
            withPermit {
                HttpIdempotencyTable.selectAll().single()[HttpIdempotencyTable.responseHeaders]
            } shouldBeEqualTo "ETag=%22claim-3%22&Location=%2Fclaims%2Fclaim-1"
        }
    }

/**
 * withPermit은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun <T> withPermit(block: () -> T): T =
        gate.withPermit(DatabaseLane.FOREGROUND, block)

}
