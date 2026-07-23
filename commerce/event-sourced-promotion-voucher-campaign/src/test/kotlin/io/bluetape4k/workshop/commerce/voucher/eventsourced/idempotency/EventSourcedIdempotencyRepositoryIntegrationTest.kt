package io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.CommandExecutionResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedCommand
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedCommandDecision
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedCommandService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.ExposedCommandTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendFences
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRead
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExposedEventStoreTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.IdempotencyReceipts
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamHeads
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventSourcedIdempotencyRepositoryIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private lateinit var database: Database
    private val repository = EventSourcedIdempotencyRepository()
    private lateinit var store: EventStoreRepository
    private lateinit var commands: EventSourcedCommandService

    @BeforeAll
    fun connectPostgres() {
        database =
            Database.connect(
                url = postgres.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = requireNotNull(postgres.username),
                password = requireNotNull(postgres.password),
            )
        store = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
        commands = EventSourcedCommandService(ExposedCommandTransactionRunner(database), repository, store)
    }

    @BeforeEach
    fun createSchema() =
        transaction(database) {
            SchemaUtils.create(EventLog, StreamHeads, AppendFences, IdempotencyReceipts)
        }

    @AfterEach
    fun dropSchema() =
        transaction(database) {
            SchemaUtils.drop(EventLog, StreamHeads, AppendFences, IdempotencyReceipts)
        }

    @Test
    fun `same scope and fingerprint replays one terminal descriptor`() {
        // Given
        val scope = scope()
        val fingerprint = ReceiptDigest.sha256("canonical-request")
        val owner = transaction(database) { repository.acquire(scope, fingerprint, NOW) } as ReceiptAcquireResult.Owner

        // When
        transaction(database) { repository.finalize(scope, fingerprint, owner.token, NOW.plusSeconds(1), DESCRIPTOR) }
        val replay = transaction(database) { repository.acquire(scope, fingerprint, NOW.plusSeconds(2)) }

        // Then
        replay shouldBeEqualTo ReceiptAcquireResult.Replay(DESCRIPTOR)
    }

    @Test
    fun `same scope and changed fingerprint conflicts`() {
        // Given
        val scope = scope()
        transaction(database) { repository.acquire(scope, ReceiptDigest.sha256("first"), NOW) }

        // When
        val result =
            transaction(database) {
                repository.acquire(scope, ReceiptDigest.sha256("second"), NOW.plusSeconds(1))
            }

        // Then
        result shouldBeEqualTo ReceiptAcquireResult.FingerprintConflict
    }

    @Test
    fun `expired lease permits takeover and rejects stale owner finalize`() {
        // Given
        val scope = scope()
        val fingerprint = ReceiptDigest.sha256("canonical-request")
        val first = transaction(database) { repository.acquire(scope, fingerprint, NOW) } as ReceiptAcquireResult.Owner
        val second =
            transaction(database) {
                repository.acquire(scope, fingerprint, NOW.plusSeconds(91))
            } as ReceiptAcquireResult.Owner

        // When
        val staleFinalized =
            transaction(database) {
                repository.finalize(scope, fingerprint, first.token, NOW.plusSeconds(92), DESCRIPTOR)
            }
        val currentFinalized =
            transaction(database) {
                repository.finalize(scope, fingerprint, second.token, NOW.plusSeconds(92), DESCRIPTOR)
            }

        // Then
        staleFinalized.shouldBeFalse()
        currentFinalized.shouldBeTrue()
    }

    @Test
    fun `same key is isolated by principal digest`() {
        // Given
        val fingerprint = ReceiptDigest.sha256("canonical-request")
        val firstScope = scope(principal = "principal-a")
        val secondScope = scope(principal = "principal-b")

        // When
        val first = transaction(database) { repository.acquire(firstScope, fingerprint, NOW) }
        val second = transaction(database) { repository.acquire(secondScope, fingerprint, NOW) }

        // Then
        (first is ReceiptAcquireResult.Owner).shouldBeTrue()
        (second is ReceiptAcquireResult.Owner).shouldBeTrue()
    }

    @Test
    fun `event append and terminal receipt finalize roll back together`() {
        // Given
        val scope = scope()
        val fingerprint = ReceiptDigest.sha256("canonical-request")
        val owner = transaction(database) { repository.acquire(scope, fingerprint, NOW) } as ReceiptAcquireResult.Owner
        val stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())

        // When
        assertFailsWith<IllegalStateException> {
            transaction(database) {
                val append = store.appendAll(listOf(ExpectedAppend(stream, 0, listOf(event()))))
                (append is AppendResult.Appended).shouldBeTrue()
                repository.finalize(scope, fingerprint, owner.token, NOW.plusSeconds(1), DESCRIPTOR).shouldBeTrue()
                error("force command rollback")
            }
        }

        // Then
        store.load(EventStoreRead(stream, afterVersion = 0)).events shouldBeEqualTo emptyList()
        val afterRollback = transaction(database) { repository.acquire(scope, fingerprint, NOW.plusSeconds(2)) }
        (afterRollback is ReceiptAcquireResult.InProgress).shouldBeTrue()
    }

    @Test
    fun `concurrent same receipt appends one event and replays one descriptor`() {
        // Given
        val scope = scope()
        val fingerprint = ReceiptDigest.sha256("canonical-request")
        val stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val command =
            EventSourcedCommand(
                scope = scope,
                fingerprint = fingerprint,
                acquiredAt = NOW,
                decideAfterRehydrate = {
                    EventSourcedCommandDecision(listOf(ExpectedAppend(stream, 0, listOf(event()))), DESCRIPTOR)
                },
            )
        val barrier = CyclicBarrier(2)
        val results = ConcurrentLinkedQueue<CommandExecutionResult>()

        // When
        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .add {
                barrier.await(5, TimeUnit.SECONDS)
                results.add(commands.execute(command))
            }.run()

        // Then
        results.count { it is CommandExecutionResult.Executed } shouldBeEqualTo 1
        store.load(EventStoreRead(stream, afterVersion = 0)).events.size shouldBeEqualTo 1
        val replay = transaction(database) { repository.acquire(scope, fingerprint, NOW.plusSeconds(1)) }
        (replay is ReceiptAcquireResult.Replay).shouldBeTrue()
    }

    private fun scope(principal: String = "principal-a") =
        ReceiptScope(
            tenantId = TenantId("tenant-a"),
            principalDigest = ReceiptDigest.sha256(principal),
            operation = "campaign.create",
            resourceId = "campaign-a",
            keyDigest = ReceiptDigest.sha256("idempotency-key"),
        )

    private fun event() =
        EventToAppend(
            eventId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc099"),
            eventType = "campaign.created",
            schemaVersion = 1,
            payload = EventPayload("{}"),
        )

    companion object {
        private val NOW = Instant.parse("2026-07-23T10:00:00Z")
        private val DESCRIPTOR =
            TerminalDescriptor(
                outcome = ReceiptOutcome.VOUCHER_ALLOCATED,
                status = 201,
                allocationId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc001"),
                generationKeyVersion = 3,
                verificationKeyVersion = 3,
            )
    }
}
