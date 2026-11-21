package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.idempotency.Digest
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyAcquireResult
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyRecord
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyScope
import io.bluetape4k.workshop.commerce.voucher.idempotency.OwnerToken
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherIdempotencyStore
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.*
import java.util.function.Supplier

/**
 * IdempotentVoucherCommandServiceTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class IdempotentVoucherCommandServiceTest {

    companion object: KLogging() {
/**
 * NOW은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-19T10:00:00Z")
/**
 * OWNER은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val OWNER = OwnerToken(ByteArray(32) { 7 })
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
            keyDigest = Digest.sha256("raw-key"),
        )
/**
 * COMMAND은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val COMMAND = IdempotentVoucherCommand(SCOPE, Digest.sha256("request"))
/**
 * RESPONSE은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val RESPONSE = StoredHttpResponse(
            responseKind = VoucherResponseKind.ALLOCATION_ACCEPTED,
            status = 201,
            headers = mapOf("Location" to "/claims/claim-1"),
            aggregateId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890bc"),
            allocationId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890cd"),
            aggregateRevision = 3,
            generationKeyVersion = 5,
            verificationKeyVersion = 7,
        )
/**
 * RETRYABLE은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val RETRYABLE = RESPONSE.copy(
            responseKind = VoucherResponseKind.RATE_LIMITED,
            status = 429,
            headers = mapOf("Retry-After" to "1"),
            allocationId = null,
            generationKeyVersion = null,
            verificationKeyVersion = null,
        )
    }

    @Test
/**
 * 이 시나리오는 "Spring creates the service without failure injection function beans" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Spring creates the service without failure injection function beans`() {
        AnnotationConfigApplicationContext().use { context ->
            context.registerBean(VoucherIdempotencyStore::class.java, Supplier { FakeStore() })
            context.registerBean(VoucherTransactionRunner::class.java, Supplier { RecordingTransactions() })
            context.registerBean(Clock::class.java, Supplier { Clock.fixed(NOW, ZoneOffset.UTC) })
            context.register(IdempotentVoucherCommandService::class.java)

            context.refresh()

            context.getBean<IdempotentVoucherCommandService>().javaClass shouldBeEqualTo
                    IdempotentVoucherCommandService::class.java
        }
    }

    @Test
/**
 * 이 시나리오는 "replay lookup admission acquire and atomic business finalize follow permit boundaries"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `replay lookup admission acquire and atomic business finalize follow permit boundaries`() {
        val events = mutableListOf<String>()
        val transactions = RecordingTransactions(events)
        val store = FakeStore(events) { transactions.active }
        val service = service(store, transactions)

        val result = service.execute(
            command = COMMAND,
            admission = { events += "admission"; null },
            business = {
                transactions.active.shouldBeTrue()
                events += "business"
                RESPONSE
            },
        )

        result shouldBeEqualTo IdempotentCommandResult.Completed(RESPONSE, replayed = false)
        events shouldBeEqualTo listOf(
            "tx:start",
            "lookup",
            "tx:commit",
            "admission",
            "tx:start",
            "acquire",
            "tx:commit",
            "tx:start",
            "owner-check",
            "business",
            "finalize",
            "tx:commit",
        )
        store.businessFinalizeSharedTransaction.shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "commit after response loss replays without a second business effect" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `commit after response loss replays without a second business effect`() {
        val store = FakeStore()
        var effects = 0
        val service = service(
            store = store,
            transactions = RecordingTransactions(),
            cutPoint = { point ->
                if (point == IdempotencyCutPoint.AFTER_COMMIT_BEFORE_RESPONSE) error("response lost")
            },
        )

        assertFailsWith<IllegalStateException> {
            service.execute(COMMAND, admission = { null }) {
                effects++
                RESPONSE
            }
        }
        val replay = service(store, RecordingTransactions())
            .execute(COMMAND, admission = { null }) {
                effects++
                RESPONSE
            }

        replay shouldBeEqualTo IdempotentCommandResult.Completed(RESPONSE, replayed = true)
        effects shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "retryable admission rejection never creates an owner" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retryable admission rejection never creates an owner`() {
        val store = FakeStore()
        val result = service(store, RecordingTransactions())
            .execute(COMMAND, admission = { RETRYABLE }) { RESPONSE }

        result shouldBeEqualTo IdempotentCommandResult.Retryable(RETRYABLE)
        store.acquireCalled.shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "retryable business failure releases owner for same key recovery" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retryable business failure releases owner for same key recovery`() {
        val store = FakeStore()
        val service = service(store, RecordingTransactions())
        val failed = service.execute(COMMAND, admission = { null }) {
            throw RetryableVoucherCommand(RETRYABLE)
        }

        failed shouldBeEqualTo IdempotentCommandResult.Retryable(RETRYABLE)
        store.released.shouldBeTrue()

        service.execute(COMMAND, admission = { null }) { RESPONSE } shouldBeEqualTo
                IdempotentCommandResult.Completed(RESPONSE, replayed = false)
    }

/**
 * service은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun service(
        store: FakeStore,
        transactions: RecordingTransactions,
        cutPoint: (IdempotencyCutPoint) -> Unit = {},
    ) =
        IdempotentVoucherCommandService(
            idempotency = store,
            transactions = transactions,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            ownerTokens = { OWNER },
            cutPoint = cutPoint,
        )

/**
 * RecordingTransactions 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingTransactions(
/**
 * events은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val events: MutableList<String> = mutableListOf(),
    ): VoucherTransactionRunner {
/**
 * active은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var active = false

/**
 * foregroundTransaction은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun <T> foregroundTransaction(block: () -> T): T {
            events += "tx:start"
            active = true

            return try {
                block().also { events += "tx:commit" }
            } finally {
                active = false
            }
        }
    }

/**
 * FakeStore 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class FakeStore(
/**
 * events은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val events: MutableList<String> = mutableListOf(),
/**
 * transactionActive은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val transactionActive: () -> Boolean = { true },
    ): VoucherIdempotencyStore {
/**
 * terminal은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private var terminal: StoredHttpResponse? = null
/**
 * owned은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private var owned = false
/**
 * acquireCalled은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var acquireCalled = false
/**
 * released은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var released = false
/**
 * businessFinalizeSharedTransaction은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var businessFinalizeSharedTransaction = false

/**
 * lookup은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun lookup(
            scope: IdempotencyScope,
            fingerprint: Digest,
        ): IdempotencyAcquireResult? {
            events += "lookup"
            return terminal?.let(IdempotencyAcquireResult::Replay)
        }

/**
 * acquire은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun acquire(
            scope: IdempotencyScope,
            fingerprint: Digest,
            now: Instant,
            ownerToken: OwnerToken,
            lease: Duration,
            commandTimeout: Duration,
            retention: Duration,
        ): IdempotencyAcquireResult {
            events += "acquire"
            acquireCalled = true
            owned = true
            released = false
            return IdempotencyAcquireResult.Owner(ownerToken, now.plus(lease))
        }

/**
 * isOwner은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun isOwner(
            scope: IdempotencyScope,
            ownerToken: OwnerToken,
            now: Instant,
        ): Boolean {
            events += "owner-check"
            return owned
        }

/**
 * finalize은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun finalize(
            scope: IdempotencyScope,
            ownerToken: OwnerToken,
            now: Instant,
            response: StoredHttpResponse,
        ): Boolean {
            events += "finalize"
            businessFinalizeSharedTransaction = transactionActive()
            terminal = response
            owned = false
            return true
        }

/**
 * release은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun release(scope: IdempotencyScope, ownerToken: OwnerToken): Boolean {
            released = true
            owned = false
            return true
        }

/**
 * find은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun find(scope: IdempotencyScope): IdempotencyRecord? = null

/**
 * cleanupExpired은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun cleanupExpired(now: Instant, limit: Int): Int = 0
    }
}
