package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * ProjectionRebuildRepositoryIntegrationTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class ProjectionRebuildRepositoryIntegrationTest {

    private companion object: KLogging() {
/**
 * postgres은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }

/**
 * PROJECTION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROJECTION = "voucher-lifecycle"
/**
 * FIRST_POSITION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val FIRST_POSITION = 1L
/**
 * TARGET_POSITION은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TARGET_POSITION = 2L
/**
 * AWAIT_TIMEOUT_SECONDS은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val AWAIT_TIMEOUT_SECONDS = 5L
/**
 * CANONICAL_DIGEST은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CANONICAL_DIGEST = ReceiptDigest.sha256("voucher-projection-generation-v1")
/**
 * CORRUPT_DIGEST은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CORRUPT_DIGEST = ReceiptDigest.sha256("voucher-projection-generation-corrupt-v1")
/**
 * NOW은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-23T13:00:00Z")
    }

/**
 * repository은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = ProjectionRebuildRepository()
/**
 * maintenance은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val maintenance = ProjectionRebuildMaintenance()
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

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-projection-rebuild")
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() {
        postgresDatabase.close()
    }

    @BeforeEach
    fun createSchema() {
        transaction(database) {
            SchemaUtils.create(ProjectionGenerations, ActiveProjectionGenerations)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction(database) {
            SchemaUtils.drop(ActiveProjectionGenerations, ProjectionGenerations)
        }
    }

    @Test
/**
 * 이 시나리오는 "validated candidate activates only when the expected active pointer revision still
 * matches" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `validated candidate activates only when the expected active pointer revision still matches`() {
        val active = transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { repository.start(PROJECTION, TARGET_POSITION, NOW) }
        val candidateKey = candidate.key

        transaction(database) {
            repository.advance(
                key = candidateKey,
                cursor = cursor(candidate, expectedPosition = 0L, position = FIRST_POSITION),
                now = NOW,
            ).shouldBeTrue()

            repository.advance(
                key = candidateKey,
                cursor = cursor(candidate, expectedPosition = FIRST_POSITION, position = TARGET_POSITION),
                now = NOW,
            ).shouldBeTrue()

            repository.beginValidation(
                key = candidateKey,
                fencingToken = candidate.fencingToken,
                cancellationRevision = candidate.cancellationRevision,
                canonicalDigest = CANONICAL_DIGEST,
                now = NOW,
            ).shouldBeTrue()
        }

        val result = transaction(database) {
            repository.activate(
                key = candidateKey,
                expectedPointerRevision = active.revision,
                targetHead = TARGET_POSITION,
                canonicalDigest = CANONICAL_DIGEST,
                now = NOW,
            )
        }

        result shouldBeEqualTo ProjectionActivationResult.Activated
        transaction(database) {
            findActive(PROJECTION)
        }?.generation shouldBeEqualTo candidateKey.generation

        transaction(database) {
            findGeneration(ProjectionKey(PROJECTION, active.generation))
        }?.state shouldBeEqualTo ProjectionGenerationState.RETIRED
    }

    @Test
/**
 * 이 시나리오는 "corrupt validation digest cannot activate a completed candidate" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `corrupt validation digest cannot activate a completed candidate`() {
        val active = transaction(database) {
            repository.initializeActive(PROJECTION, NOW)
        }
        val candidate = transaction(database) {
            repository.start(PROJECTION, TARGET_POSITION, NOW)
        }

        transaction(database) {
            repository.advance(
                key = candidate.key,
                cursor = cursor(candidate, expectedPosition = 0L, position = FIRST_POSITION),
                now = NOW,
            ).shouldBeTrue()

            repository.advance(
                key = candidate.key,
                cursor = cursor(candidate, expectedPosition = FIRST_POSITION, position = TARGET_POSITION),
                now = NOW,
            ).shouldBeTrue()

            repository.beginValidation(
                key = candidate.key,
                fencingToken = candidate.fencingToken,
                cancellationRevision = candidate.cancellationRevision,
                canonicalDigest = CANONICAL_DIGEST,
                now = NOW,
            ).shouldBeTrue()
        }

        transaction(database) {
            repository.activate(
                key = candidate.key,
                expectedPointerRevision = active.revision,
                targetHead = TARGET_POSITION,
                canonicalDigest = CORRUPT_DIGEST,
                now = NOW,
            )
        } shouldBeEqualTo ProjectionActivationResult.CandidateNotReady

        transaction(database) {
            findActive(PROJECTION)
        }?.generation shouldBeEqualTo active.generation
    }

    @Test
/**
 * 이 시나리오는 "concurrent activation attempts commit one active pointer transition" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `concurrent activation attempts commit one active pointer transition`() {
        val active = transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { repository.start(PROJECTION, TARGET_POSITION, NOW) }
        validate(candidate)
        val barrier = CyclicBarrier(2)
        val results = ConcurrentLinkedQueue<ProjectionActivationResult>()

        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .add {
                barrier.await(AWAIT_TIMEOUT_SECONDS.seconds)
                results.add(
                    transaction(database) {
                        repository.activate(
                            key = candidate.key,
                            expectedPointerRevision = active.revision,
                            targetHead = TARGET_POSITION,
                            canonicalDigest = CANONICAL_DIGEST,
                            now = NOW,
                        )
                    },
                )
            }.run()

        results.count { it == ProjectionActivationResult.Activated } shouldBeEqualTo 1
        results.count { it == ProjectionActivationResult.StalePointer } shouldBeEqualTo 1

        transaction(database) {
            findActive(PROJECTION)
        }?.generation shouldBeEqualTo candidate.key.generation
    }

    @Test
/**
 * 이 시나리오는 "cancellation increments the token and stale completion cannot change the candidate" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `cancellation increments the token and stale completion cannot change the candidate`() {
        transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { repository.start(PROJECTION, TARGET_POSITION, NOW) }
        val cancelling = transaction(database) {
            repository.requestCancellation(candidate.key, NOW)
        }.shouldNotBeNull()

        cancelling.fencingToken shouldBeEqualTo candidate.fencingToken + 1
        transaction(database) {
            repository.completeCancellation(candidate.key, candidate.fencingToken, NOW).shouldBeFalse()
            repository.completeCancellation(candidate.key, cancelling.fencingToken, NOW).shouldBeTrue()
        }
        transaction(database) {
            findGeneration(candidate.key)
        }?.state shouldBeEqualTo ProjectionGenerationState.CANCELLED
    }

    @Test
/**
 * 이 시나리오는 "stale cancellation revision cannot advance an otherwise current candidate" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stale cancellation revision cannot advance an otherwise current candidate`() {
        transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) {
            repository.start(PROJECTION, TARGET_POSITION, NOW)
        }

        transaction(database) {
            repository.advance(
                key = candidate.key,
                cursor = cursor(
                    candidate,
                    cancellationRevision = candidate.cancellationRevision + 1,
                    expectedPosition = 0L,
                    position = FIRST_POSITION,
                ),
                now = NOW,
            ).shouldBeFalse()
        }
        transaction(database) {
            findGeneration(candidate.key)
        }?.currentPosition shouldBeEqualTo 0L
    }

    @Test
/**
 * 이 시나리오는 "restart recovery converges a cancelling candidate to cancelled" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `restart recovery converges a cancelling candidate to cancelled`() {
        transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) {
            repository.start(PROJECTION, TARGET_POSITION, NOW)
        }
        transaction(database) {
            repository.requestCancellation(candidate.key, NOW)
        }

        val restartedMaintenance = ProjectionRebuildMaintenance()

        val recovered = transaction(database) {
            restartedMaintenance.recover(candidate.key, NOW + 1.seconds()).shouldNotBeNull()
        }

        recovered.state shouldBeEqualTo ProjectionGenerationState.CANCELLED
        recovered.fencingToken shouldBeEqualTo candidate.fencingToken + 1
    }

    @Test
/**
 * 이 시나리오는 "only retryable failure resumes the same candidate with a new fencing token" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `only retryable failure resumes the same candidate with a new fencing token`() {
        transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) {
            repository.start(PROJECTION, TARGET_POSITION, NOW)
        }

        transaction(database) {
            repository.fail(
                key = candidate.key,
                fencingToken = candidate.fencingToken,
                retryable = true,
                now = NOW,
            ).shouldBeTrue()
        }
        val resumed = transaction(database) {
            repository.resume(candidate.key, NOW).shouldNotBeNull()
        }

        resumed.state shouldBeEqualTo ProjectionGenerationState.BUILDING
        resumed.fencingToken shouldBeEqualTo candidate.fencingToken + 1
    }

    @Test
/**
 * 이 시나리오는 "retention removes only retired generations without changing the active pointer" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retention removes only retired generations without changing the active pointer`() {
        val active = transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { repository.start(PROJECTION, TARGET_POSITION, NOW) }

        transaction(database) {
            repository.advance(
                key = candidate.key,
                cursor = cursor(candidate, expectedPosition = 0L, position = FIRST_POSITION),
                now = NOW,
            ).shouldBeTrue()
            repository.advance(
                key = candidate.key,
                cursor = cursor(candidate, expectedPosition = FIRST_POSITION, position = TARGET_POSITION),
                now = NOW,
            ).shouldBeTrue()
            repository.beginValidation(
                key = candidate.key,
                fencingToken = candidate.fencingToken,
                cancellationRevision = candidate.cancellationRevision,
                canonicalDigest = CANONICAL_DIGEST,
                now = NOW,
            ).shouldBeTrue()
            repository.activate(
                key = candidate.key,
                expectedPointerRevision = active.revision,
                targetHead = TARGET_POSITION,
                canonicalDigest = CANONICAL_DIGEST,
                now = NOW,
            ) shouldBeEqualTo ProjectionActivationResult.Activated
        }

        transaction(database) {
            maintenance.purgeRetired(PROJECTION, NOW + 1.seconds(), batchSize = 1)
        } shouldBeEqualTo 1
        transaction(database) { findGeneration(activeAsKey(active)).shouldBeNull() }
        transaction(database) { findActive(PROJECTION) }?.generation shouldBeEqualTo candidate.key.generation
    }

/**
 * activeAsKey은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun activeAsKey(active: ActiveProjectionGeneration): ProjectionKey =
        ProjectionKey(active.projection, active.generation)

/**
 * validate은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun validate(candidate: ProjectionGeneration) {
        transaction(database) {
            repository.advance(
                key = candidate.key,
                cursor = cursor(candidate, expectedPosition = 0L, position = FIRST_POSITION),
                now = NOW,
            ).shouldBeTrue()
            repository.advance(
                key = candidate.key,
                cursor = cursor(candidate, expectedPosition = FIRST_POSITION, position = TARGET_POSITION),
                now = NOW,
            ).shouldBeTrue()
            repository.beginValidation(
                key = candidate.key,
                fencingToken = candidate.fencingToken,
                cancellationRevision = candidate.cancellationRevision,
                canonicalDigest = CANONICAL_DIGEST,
                now = NOW,
            ).shouldBeTrue()
        }
    }

/**
 * cursor은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun cursor(
        candidate: ProjectionGeneration,
        cancellationRevision: Long = candidate.cancellationRevision,
        expectedPosition: Long,
        position: Long,
    ): ProjectionRebuildCursor =
        ProjectionRebuildCursor(
            fencingToken = candidate.fencingToken,
            cancellationRevision = cancellationRevision,
            expectedPosition = expectedPosition,
            position = position,
        )
}
