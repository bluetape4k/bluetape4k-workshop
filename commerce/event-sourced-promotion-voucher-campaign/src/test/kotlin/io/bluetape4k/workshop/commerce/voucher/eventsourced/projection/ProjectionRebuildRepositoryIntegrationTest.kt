package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ProjectionRebuildRepositoryIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = ProjectionRebuildRepository()
    private val maintenance = ProjectionRebuildMaintenance()
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        database =
            Database.connect(
                url = postgres.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = requireNotNull(postgres.username),
                password = requireNotNull(postgres.password),
            )
    }

    @BeforeEach
    fun createSchema() =
        transaction(database) { SchemaUtils.create(ProjectionGenerations, ActiveProjectionGenerations) }

    @AfterEach
    fun dropSchema() =
        transaction(database) { SchemaUtils.drop(ActiveProjectionGenerations, ProjectionGenerations) }

    @Test
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
        }
        transaction(database) {
            repository.advance(
                key = candidateKey,
                cursor = cursor(candidate, expectedPosition = FIRST_POSITION, position = TARGET_POSITION),
                now = NOW,
            ).shouldBeTrue()
        }
        transaction(database) {
            repository.beginValidation(
                key = candidateKey,
                fencingToken = candidate.fencingToken,
                cancellationRevision = candidate.cancellationRevision,
                canonicalDigest = CANONICAL_DIGEST,
                now = NOW,
            ).shouldBeTrue()
        }
        val result =
            transaction(database) {
                repository.activate(
                    key = candidateKey,
                    expectedPointerRevision = active.revision,
                    targetHead = TARGET_POSITION,
                    canonicalDigest = CANONICAL_DIGEST,
                    now = NOW,
                )
            }

        result shouldBeEqualTo ProjectionActivationResult.Activated
        transaction(database) { findActive(PROJECTION) }?.generation shouldBeEqualTo candidateKey.generation
        transaction(database) { findGeneration(ProjectionKey(PROJECTION, active.generation)) }?.state shouldBeEqualTo
            ProjectionGenerationState.RETIRED
    }

    @Test
    fun `corrupt validation digest cannot activate a completed candidate`() {
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
        transaction(database) { findActive(PROJECTION) }?.generation shouldBeEqualTo active.generation
    }

    @Test
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
                barrier.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
        transaction(database) { findActive(PROJECTION) }?.generation shouldBeEqualTo candidate.key.generation
    }

    @Test
    fun `cancellation increments the token and stale completion cannot change the candidate`() {
        transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { repository.start(PROJECTION, TARGET_POSITION, NOW) }
        val cancelling = transaction(database) { repository.requestCancellation(candidate.key, NOW) }

        requireNotNull(cancelling).fencingToken shouldBeEqualTo candidate.fencingToken + 1
        transaction(database) {
            repository.completeCancellation(candidate.key, candidate.fencingToken, NOW).shouldBeFalse()
        }
        transaction(database) {
            repository.completeCancellation(candidate.key, cancelling.fencingToken, NOW).shouldBeTrue()
        }
        transaction(database) { findGeneration(candidate.key) }?.state shouldBeEqualTo
            ProjectionGenerationState.CANCELLED
    }

    @Test
    fun `stale cancellation revision cannot advance an otherwise current candidate`() {
        transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { repository.start(PROJECTION, TARGET_POSITION, NOW) }

        transaction(database) {
            repository.advance(
                key = candidate.key,
                cursor =
                    cursor(
                        candidate,
                        cancellationRevision = candidate.cancellationRevision + 1,
                        expectedPosition = 0L,
                        position = FIRST_POSITION,
                    ),
                now = NOW,
            ).shouldBeFalse()
        }
        transaction(database) { findGeneration(candidate.key) }?.currentPosition shouldBeEqualTo 0L
    }

    @Test
    fun `restart recovery converges a cancelling candidate to cancelled`() {
        transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { repository.start(PROJECTION, TARGET_POSITION, NOW) }
        transaction(database) { repository.requestCancellation(candidate.key, NOW) }
        val restartedMaintenance = ProjectionRebuildMaintenance()

        val recovered = transaction(database) { restartedMaintenance.recover(candidate.key, NOW.plusSeconds(1)) }

        requireNotNull(recovered).state shouldBeEqualTo ProjectionGenerationState.CANCELLED
        recovered.fencingToken shouldBeEqualTo candidate.fencingToken + 1
    }

    @Test
    fun `only retryable failure resumes the same candidate with a new fencing token`() {
        transaction(database) { repository.initializeActive(PROJECTION, NOW) }
        val candidate = transaction(database) { repository.start(PROJECTION, TARGET_POSITION, NOW) }

        transaction(database) {
            repository.fail(
                key = candidate.key,
                fencingToken = candidate.fencingToken,
                retryable = true,
                now = NOW,
            ).shouldBeTrue()
        }
        val resumed = transaction(database) { repository.resume(candidate.key, NOW) }

        requireNotNull(resumed).state shouldBeEqualTo ProjectionGenerationState.BUILDING
        resumed.fencingToken shouldBeEqualTo candidate.fencingToken + 1
    }

    @Test
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
            maintenance.purgeRetired(PROJECTION, NOW.plusSeconds(1), batchSize = 1)
        } shouldBeEqualTo 1
        transaction(database) { (findGeneration(activeAsKey(active)) == null).shouldBeTrue() }
        transaction(database) { findActive(PROJECTION) }?.generation shouldBeEqualTo candidate.key.generation
    }

    private fun activeAsKey(active: ActiveProjectionGeneration): ProjectionKey =
        ProjectionKey(active.projection, active.generation)

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

    private companion object {
        private const val PROJECTION = "voucher-lifecycle"
        private const val FIRST_POSITION = 1L
        private const val TARGET_POSITION = 2L
        private const val AWAIT_TIMEOUT_SECONDS = 5L
        private val CANONICAL_DIGEST = ReceiptDigest.sha256("voucher-projection-generation-v1")
        private val CORRUPT_DIGEST = ReceiptDigest.sha256("voucher-projection-generation-corrupt-v1")
        private val NOW = Instant.parse("2026-07-23T13:00:00Z")
    }
}
