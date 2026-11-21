package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.OperatorAudits
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGenerationState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionRebuildRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * OperatorAuditRepositoryIntegrationTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OperatorAuditRepositoryIntegrationTest {

    private companion object: KLogging() {
/**
 * postgres은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }

/**
 * PROJECTION은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROJECTION = "voucher-lifecycle"
/**
 * NOW은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-23T14:00:00Z")
    }

/**
 * postgresDatabase은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
/**
 * database은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var database: Database
/**
 * rebuilds은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val rebuilds = ProjectionRebuildRepository()
/**
 * audits은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val audits = OperatorAuditRepository()
/**
 * operator은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var operator: EventSourcedRebuildOperator

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-operator-audit")
        database = postgresDatabase.database
        operator = EventSourcedRebuildOperator(
            EventSourcedPermitTransactionRunner(
                database,
                EventSourcedDatabasePermitGate(),
                EventSourcedDatabaseLane.FOREGROUND,
            ),
            rebuilds,
            audits,
        )
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @BeforeEach
    fun setUp() {
        transaction(database) {
            SchemaUtils.create(ProjectionGenerations, ActiveProjectionGenerations, OperatorAudits)
        }
    }

    @AfterEach
    fun tearDown() {
        transaction(database) {
            SchemaUtils.drop(OperatorAudits, ActiveProjectionGenerations, ProjectionGenerations)
        }
    }

    @Test
/**
 * 이 시나리오는 "duplicate audit insertion rolls back its rebuild cancellation mutation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `duplicate audit insertion rolls back its rebuild cancellation mutation`() {
        val candidate = transaction(database) {
            rebuilds.initializeActive(PROJECTION, NOW)
            rebuilds.start(PROJECTION, targetPosition = 1, now = NOW)
        }
        log.debug { "candidate=$candidate" }

        val audit = audit(candidate.key.generation)
        transaction(database) {
            audits.append(audit)
        }

        assertFailsWith<Exception> {
            operator.cancel(cancellation(candidate.key.generation))
        }

        transaction(database) {
            ProjectionGenerations
                .selectAll()
                .where { ProjectionGenerations.generation eq candidate.key.generation }
                .single()[ProjectionGenerations.state] shouldBeEqualTo ProjectionGenerationState.BUILDING

            OperatorAudits.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
/**
 * 이 시나리오는 "fenced cancellation writes applied audit with its state transition" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fenced cancellation writes applied audit with its state transition`() {
        val candidate = transaction(database) {
            rebuilds.initializeActive(PROJECTION, NOW)
            rebuilds.start(PROJECTION, targetPosition = 1, now = NOW)
        }

        val outcome = operator.cancel(cancellation(candidate.key.generation))

        outcome.state shouldBeEqualTo ProjectionGenerationState.CANCELLING
        outcome.audit.outcome shouldBeEqualTo OperatorAuditOutcome.APPLIED

        transaction(database) {
            OperatorAudits.selectAll().single().let { row ->
                row[OperatorAudits.beforeState] shouldBeEqualTo ProjectionGenerationState.BUILDING
                row[OperatorAudits.afterState] shouldBeEqualTo ProjectionGenerationState.CANCELLING
                row[OperatorAudits.expectedFencingToken] shouldBeEqualTo candidate.fencingToken
            }
        }
    }

/**
 * audit은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun audit(generation: Long): OperatorAuditEntry =
        OperatorAuditEntry(
            identity = OperatorAuditIdentity(
                actorDigest = ReceiptDigest.sha256("operator-a"),
                tenant = "tenant-a",
                requestDigest = ReceiptDigest.sha256("request-a"),
            ),
            action = OperatorAuditAction.REBUILD_CANCELLED,
            target = OperatorAuditTarget(PROJECTION, generation, expectedFencingToken = 1),
            transition = OperatorAuditTransition(
                beforeState = ProjectionGenerationState.BUILDING,
                afterState = ProjectionGenerationState.CANCELLING,
                checkpointPosition = 0,
                streamPosition = 1,
                reasonClass = null,
            ),
            result = OperatorAuditResult(OperatorAuditOutcome.APPLIED, NOW),
        )

/**
 * cancellation은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun cancellation(generation: Long): RebuildCancellationCommand =
        RebuildCancellationCommand(
            identity = OperatorAuditIdentity(
                actorDigest = ReceiptDigest.sha256("operator-a"),
                tenant = "tenant-a",
                requestDigest = ReceiptDigest.sha256("request-a"),
            ),
            target = OperatorAuditTarget(PROJECTION, generation, expectedFencingToken = 1),
            checkpointPosition = 0,
            streamPosition = 1,
            occurredAt = NOW,
        )
}
