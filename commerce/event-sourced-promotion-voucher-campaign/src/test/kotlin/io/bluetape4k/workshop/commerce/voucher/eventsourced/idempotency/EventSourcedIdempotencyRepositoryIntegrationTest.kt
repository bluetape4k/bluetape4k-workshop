package io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.CommandExecutionResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedCommand
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedCommandDecision
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.EventSourcedCommandService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.application.ExposedCommandTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
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
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * EventSourcedIdempotencyRepositoryIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를
 * 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedIdempotencyRepositoryIntegrationTest {

    companion object: KLogging() {
/**
 * postgres은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }

/**
 * NOW은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-23T10:00:00Z")
/**
 * DESCRIPTOR은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val DESCRIPTOR = TerminalDescriptor(
            outcome = ReceiptOutcome.VOUCHER_ALLOCATED,
            status = 201,
            allocationId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc001"),
            keyVersions = TerminalKeyVersions(
                generationKeyVersion = 3,
                verificationKeyVersion = 3,
            ),
        )
    }

/**
 * postgresDatabase은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
/**
 * database은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var database: Database
/**
 * repository은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = EventSourcedIdempotencyRepository()
/**
 * store은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var store: EventStoreRepository
/**
 * commands은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var commands: EventSourcedCommandService

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-idempotency")
        database = postgresDatabase.database
        store = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
        commands = EventSourcedCommandService(
            ExposedCommandTransactionRunner(database, EventSourcedDatabasePermitGate()),
            repository,
            store,
        )
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @BeforeEach
    fun createSchema() = transaction(database) {
        SchemaUtils.create(EventLog, StreamHeads, AppendFences, IdempotencyReceipts)
    }

    @AfterEach
    fun dropSchema() = transaction(database) {
        SchemaUtils.drop(EventLog, StreamHeads, AppendFences, IdempotencyReceipts)
    }

    @Test
/**
 * 이 시나리오는 "same scope and fingerprint replays one terminal descriptor" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same scope and fingerprint replays one terminal descriptor`() {
        // 준비
        val scope = scope()
        val fingerprint = ReceiptDigest.sha256("canonical-request")
        val owner = transaction(database) {
            repository.acquire(scope, fingerprint, NOW)
        }.shouldBeInstanceOf<ReceiptAcquireResult.Owner>()

        // 실행
        transaction(database) {
            repository.finalize(scope, fingerprint, owner.token, NOW + 1.seconds(), DESCRIPTOR)
        }
        val replay = transaction(database) {
            repository.acquire(scope, fingerprint, NOW + 2.seconds())
        }

        // 검증
        log.debug { "replay=$replay" }
        replay shouldBeEqualTo ReceiptAcquireResult.Replay(DESCRIPTOR)
    }

    @Test
/**
 * 이 시나리오는 "same scope and changed fingerprint conflicts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same scope and changed fingerprint conflicts`() {
        // 준비
        val scope = scope()
        transaction(database) {
            repository.acquire(scope, ReceiptDigest.sha256("first"), NOW)
        }

        // 실행
        val result = transaction(database) {
            repository.acquire(scope, ReceiptDigest.sha256("second"), NOW + 1.seconds())
        }

        // 검증
        result shouldBeEqualTo ReceiptAcquireResult.FingerprintConflict
    }

    @Test
/**
 * 이 시나리오는 "expired lease permits takeover and rejects stale owner finalize" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `expired lease permits takeover and rejects stale owner finalize`() {
        // 준비
        val scope = scope()
        val fingerprint = ReceiptDigest.sha256("canonical-request")

        val first = transaction(database) {
            repository.acquire(scope, fingerprint, NOW)
        }.shouldBeInstanceOf<ReceiptAcquireResult.Owner>()

        val second = transaction(database) {
            repository.acquire(scope, fingerprint, NOW.plusSeconds(91))
        }.shouldBeInstanceOf<ReceiptAcquireResult.Owner>()

        // 실행
        val staleFinalized = transaction(database) {
            repository.finalize(scope, fingerprint, first.token, NOW.plusSeconds(92), DESCRIPTOR)
        }
        val currentFinalized = transaction(database) {
            repository.finalize(scope, fingerprint, second.token, NOW.plusSeconds(92), DESCRIPTOR)
        }

        // 검증
        staleFinalized.shouldBeFalse()
        currentFinalized.shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "same key is isolated by principal digest" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `same key is isolated by principal digest`() {
        // 준비
        val fingerprint = ReceiptDigest.sha256("canonical-request")
        val firstScope = scope(principal = "principal-a")
        val secondScope = scope(principal = "principal-b")

        // 실행
        val first = transaction(database) { repository.acquire(firstScope, fingerprint, NOW) }
        val second = transaction(database) { repository.acquire(secondScope, fingerprint, NOW) }

        // 검증
        first.shouldBeInstanceOf<ReceiptAcquireResult.Owner>()
        second.shouldBeInstanceOf<ReceiptAcquireResult.Owner>()
    }

    @Test
/**
 * 이 시나리오는 "event append and terminal receipt finalize roll back together" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `event append and terminal receipt finalize roll back together`() {
        // 준비
        val scope = scope()
        val fingerprint = ReceiptDigest.sha256("canonical-request")
        val owner = transaction(database) {
            repository.acquire(scope, fingerprint, NOW)
        }.shouldBeInstanceOf<ReceiptAcquireResult.Owner>()

        val stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())

        // 실행
        assertFailsWith<IllegalStateException> {
            transaction(database) {
                val append = store.appendAll(listOf(ExpectedAppend(stream, 0, listOf(event()))))
                append.shouldBeInstanceOf<AppendResult.Appended>()
                repository.finalize(scope, fingerprint, owner.token, NOW + 1.seconds(), DESCRIPTOR).shouldBeTrue()
                error("force command rollback")
            }
        }

        // 검증
        store.load(EventStoreRead(stream, afterVersion = 0)).events.shouldBeEmpty()
        val afterRollback = transaction(database) {
            repository.acquire(scope, fingerprint, NOW + 2.seconds())
        }
        afterRollback.shouldBeInstanceOf<ReceiptAcquireResult.InProgress>()
    }

    @Test
/**
 * 이 시나리오는 "concurrent same receipt appends one event and replays one descriptor" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `concurrent same receipt appends one event and replays one descriptor`() {
        // 준비
        val scope = scope()
        val fingerprint = ReceiptDigest.sha256("canonical-request")
        val stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val command = EventSourcedCommand(
            scope = scope,
            fingerprint = fingerprint,
            acquiredAt = NOW,
            decideAfterRehydrate = {
                EventSourcedCommandDecision(listOf(ExpectedAppend(stream, 0, listOf(event()))), DESCRIPTOR)
            },
        )
        val barrier = CyclicBarrier(2)
        val results = ConcurrentLinkedQueue<CommandExecutionResult>()

        // 실행
        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .add {
                barrier.await(5.seconds)
                results.add(commands.execute(command))
            }
            .run()

        // 검증
        results.count { it is CommandExecutionResult.Executed } shouldBeEqualTo 1
        store.load(EventStoreRead(stream, afterVersion = 0)).events shouldHaveSize 1

        val replay = transaction(database) {
            repository.acquire(scope, fingerprint, NOW + 1.seconds())
        }
        replay.shouldBeInstanceOf<ReceiptAcquireResult.Replay>()
    }

/**
 * scope은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun scope(principal: String = "principal-a"): ReceiptScope =
        ReceiptScope(
            tenantId = TenantId("tenant-a"),
            principalDigest = ReceiptDigest.sha256(principal),
            operation = "campaign.create",
            resourceId = "campaign-a",
            keyDigest = ReceiptDigest.sha256("idempotency-key"),
        )

/**
 * event은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun event(): EventToAppend =
        EventToAppend(
            eventId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc099"),
            eventType = "campaign.created",
            schemaVersion = 1,
            payload = EventPayload("{}"),
        )
}
