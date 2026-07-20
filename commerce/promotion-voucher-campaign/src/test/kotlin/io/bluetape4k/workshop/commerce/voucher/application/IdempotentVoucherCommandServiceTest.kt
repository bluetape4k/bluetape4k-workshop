package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Supplier

internal class IdempotentVoucherCommandServiceTest {
    @Test
    fun `Spring creates the service without failure injection function beans`() {
        AnnotationConfigApplicationContext().use { context ->
            context.registerBean(VoucherIdempotencyStore::class.java, Supplier { FakeStore() })
            context.registerBean(VoucherTransactionRunner::class.java, Supplier { RecordingTransactions() })
            context.registerBean(Clock::class.java, Supplier { Clock.fixed(NOW, ZoneOffset.UTC) })
            context.register(IdempotentVoucherCommandService::class.java)

            context.refresh()

            context.getBean(IdempotentVoucherCommandService::class.java).javaClass shouldBeEqualTo
                IdempotentVoucherCommandService::class.java
        }
    }

    @Test
    fun `replay lookup admission acquire and atomic business finalize follow permit boundaries`() {
        val events = mutableListOf<String>()
        val transactions = RecordingTransactions(events)
        val store = FakeStore(events) { transactions.active }
        val service = service(store, transactions)

        val result =
            service.execute(
                command = COMMAND,
                admission = { events += "admission"; null },
                business = {
                    transactions.active.shouldBeTrue()
                    events += "business"
                    RESPONSE
                },
            )

        result shouldBeEqualTo IdempotentCommandResult.Completed(RESPONSE, replayed = false)
        events shouldBeEqualTo
            listOf(
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
    fun `commit after response loss replays without a second business effect`() {
        val store = FakeStore()
        var effects = 0
        val service =
            service(
                store,
                RecordingTransactions(),
                cutPoint = { point ->
                    if (point == IdempotencyCutPoint.AFTER_COMMIT_BEFORE_RESPONSE) error("response lost")
                },
            )

        assertFailsWith<IllegalStateException> {
            service.execute(COMMAND, admission = { null }) { effects++; RESPONSE }
        }
        val replay = service(store, RecordingTransactions()).execute(COMMAND, admission = { null }) { effects++; RESPONSE }

        replay shouldBeEqualTo IdempotentCommandResult.Completed(RESPONSE, replayed = true)
        effects shouldBeEqualTo 1
    }

    @Test
    fun `retryable admission rejection never creates an owner`() {
        val store = FakeStore()
        val result = service(store, RecordingTransactions()).execute(COMMAND, admission = { RETRYABLE }) { RESPONSE }

        result shouldBeEqualTo IdempotentCommandResult.Retryable(RETRYABLE)
        store.acquireCalled.shouldBeFalse()
    }

    @Test
    fun `retryable business failure releases owner for same key recovery`() {
        val store = FakeStore()
        val service = service(store, RecordingTransactions())
        val failed =
            service.execute(COMMAND, admission = { null }) {
                throw RetryableVoucherCommand(RETRYABLE)
            }

        failed shouldBeEqualTo IdempotentCommandResult.Retryable(RETRYABLE)
        store.released.shouldBeTrue()
        service.execute(COMMAND, admission = { null }) { RESPONSE } shouldBeEqualTo
            IdempotentCommandResult.Completed(RESPONSE, replayed = false)
    }

    private fun service(
        store: FakeStore,
        transactions: RecordingTransactions,
        cutPoint: (IdempotencyCutPoint) -> Unit = {},
    ) = IdempotentVoucherCommandService(
        idempotency = store,
        transactions = transactions,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        ownerTokens = { OWNER },
        cutPoint = cutPoint,
    )

    private class RecordingTransactions(
        private val events: MutableList<String> = mutableListOf(),
    ) : VoucherTransactionRunner {
        var active = false

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

    private class FakeStore(
        private val events: MutableList<String> = mutableListOf(),
        private val transactionActive: () -> Boolean = { true },
    ) : VoucherIdempotencyStore {
        private var terminal: StoredHttpResponse? = null
        private var owned = false
        var acquireCalled = false
        var released = false
        var businessFinalizeSharedTransaction = false

        override fun lookup(
            scope: IdempotencyScope,
            fingerprint: Digest,
        ): IdempotencyAcquireResult? {
            events += "lookup"
            return terminal?.let(IdempotencyAcquireResult::Replay)
        }

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

        override fun isOwner(
            scope: IdempotencyScope,
            ownerToken: OwnerToken,
            now: Instant,
        ): Boolean {
            events += "owner-check"
            return owned
        }

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

        override fun release(scope: IdempotencyScope, ownerToken: OwnerToken): Boolean {
            released = true
            owned = false
            return true
        }

        override fun find(scope: IdempotencyScope): IdempotencyRecord? = null

        override fun cleanupExpired(now: Instant, limit: Int): Int = 0
    }

    companion object {
        private val NOW = Instant.parse("2026-07-19T10:00:00Z")
        private val OWNER = OwnerToken.of(ByteArray(32) { 7 })
        private val SCOPE =
            IdempotencyScope(
                tenantId = "tenant-a",
                principalDigest = Digest.sha256("principal-a"),
                operation = "ALLOCATE",
                resourceId = "campaign-1",
                keyDigest = Digest.sha256("raw-key"),
            )
        private val COMMAND = IdempotentVoucherCommand(SCOPE, Digest.sha256("request"))
        private val RESPONSE =
            StoredHttpResponse(
                VoucherResponseKind.ALLOCATION_ACCEPTED,
                201,
                mapOf("Location" to "/claims/claim-1"),
                UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890bc"),
                UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890cd"),
                3,
                5,
                7,
            )
        private val RETRYABLE =
            RESPONSE.copy(
                responseKind = VoucherResponseKind.RATE_LIMITED,
                status = 429,
                headers = mapOf("Retry-After" to "1"),
                allocationId = null,
                generationKeyVersion = null,
                verificationKeyVersion = null,
            )
    }
}
