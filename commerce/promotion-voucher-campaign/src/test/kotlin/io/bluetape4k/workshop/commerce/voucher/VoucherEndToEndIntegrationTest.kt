package io.bluetape4k.workshop.commerce.voucher

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.bucket4j.ratelimit.RateLimitDiagnostics
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionRecoveryPolicy
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionState
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitRejected
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionGate
import io.bluetape4k.workshop.commerce.voucher.application.AllocateVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.IdempotencyCutPoint
import io.bluetape4k.workshop.commerce.voucher.application.IdempotentCommandResult
import io.bluetape4k.workshop.commerce.voucher.application.IdempotentVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.IdempotentVoucherCommandService
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandException
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandFailure
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandTestSupport
import io.bluetape4k.workshop.commerce.voucher.config.VoucherLifecycleCoordinator
import io.bluetape4k.workshop.commerce.voucher.idempotency.Digest
import io.bluetape4k.workshop.commerce.voucher.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucher.idempotency.IdempotencyScope
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import io.bluetape4k.workshop.commerce.voucher.security.VerificationResult
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeKeyRing
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import io.bluetape4k.workshop.commerce.voucher.security.VoucherGenerationInput
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal enum class FailureScenario {
    REDIS_TIMEOUT_FLAPPING,
    DATABASE_CONNECTION_EXHAUSTION,
    POSTGRESQL_LOCK_TIMEOUT,
    RESPONSE_LOSS_REPLAY,
    CONTEXT_RESTART,
    SLOW_SSE_CONSUMER,
    WORKER_POISON_STARVATION,
    KEY_ROTATION,
    CANCELLATION,
    SHUTDOWN,
}

internal class VoucherEndToEndIntegrationTest : VoucherCommandTestSupport() {
    @ParameterizedTest(name = "{0}")
    @EnumSource(FailureScenario::class)
    fun `every failure scenario preserves PostgreSQL capacity and idempotency`(scenario: FailureScenario) {
        val campaignId = Uuid.V7.nextId()
        val userRef = Base58.randomString(8)
        createCampaign(capacity = 1, perUserLimit = 1, campaignId = campaignId)

        val capture = LogCapture()
        val allocationResult =
            capture.use {
                exercise(scenario, campaignId)
                val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, campaignId, userRef))
                assertFailsWith<VoucherCommandException> {
                    allocation.allocate(AllocateVoucherCommand(TENANT_ID, campaignId, userRef))
                }.code shouldBeEqualTo VoucherCommandFailure.PER_USER_LIMIT_REACHED
                allocated
            }

        val campaign = campaignSnapshot(campaignId)
        campaign.allocatedCount shouldBeEqualTo 1
        contributingClaims(campaignId) shouldBeEqualTo 1L
        totalClaims(campaignId) shouldBeEqualTo 1L
        gate.inUsePermits() shouldBeEqualTo 0
        gate.availablePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 12
        gate.availablePermits(DatabaseLane.WORKER) shouldBeEqualTo 1
        gate.availablePermits(DatabaseLane.SSE_MAINTENANCE) shouldBeEqualTo 3
        capture.rendered shouldNotContainSensitive listOf(
            TENANT_ID,
            userRef,
            checkNotNull(allocationResult.oneTimeCode),
            OPERATOR_SECRET,
        )
    }

    private fun exercise(
        scenario: FailureScenario,
        campaignId: java.util.UUID,
    ) {
        when (scenario) {
            FailureScenario.REDIS_TIMEOUT_FLAPPING -> exerciseRedisFlapping()
            FailureScenario.DATABASE_CONNECTION_EXHAUSTION -> exerciseDatabaseExhaustion(DatabaseLane.FOREGROUND)
            FailureScenario.POSTGRESQL_LOCK_TIMEOUT -> exerciseLockTimeout(campaignId)
            FailureScenario.RESPONSE_LOSS_REPLAY -> exerciseResponseLossReplay(campaignId)
            FailureScenario.CONTEXT_RESTART -> configureCommandRuntime()
            FailureScenario.SLOW_SSE_CONSUMER -> exerciseDatabaseExhaustion(DatabaseLane.SSE_MAINTENANCE)
            FailureScenario.WORKER_POISON_STARVATION -> exerciseWorkerIsolation()
            FailureScenario.KEY_ROTATION -> exerciseKeyRotation(campaignId)
            FailureScenario.CANCELLATION -> exerciseCancellation()
            FailureScenario.SHUTDOWN -> exerciseShutdown()
        }
    }

    private fun exerciseRedisFlapping() {
        val limiter = SequenceRateLimiter(failure(), consumed())
        val admission =
            VoucherAdmissionGate(
                rateLimiter = limiter,
                recoveryPolicy =
                    AdmissionRecoveryPolicy(
                        failureThreshold = 1,
                        recoverySuccessThreshold = 1,
                        probeInterval = Duration.ofNanos(1),
                    ),
                clock = Clock.systemUTC(),
            )

        admission.decide("v1:${Base58.randomString(8)}") shouldBeEqualTo AdmissionDecision.Proceed
        admission.state() shouldBeEqualTo AdmissionState.DEGRADED
        while (admission.state() != AdmissionState.HEALTHY) {
            admission.decide("v1:${Base58.randomString(8)}") shouldBeEqualTo AdmissionDecision.Proceed
        }
        limiter.calls shouldBeEqualTo 2
    }

    private fun exerciseDatabaseExhaustion(lane: DatabaseLane) {
        val isolated = DatabasePermitGate(1, 1, 1, acquireTimeout = Duration.ofMillis(25))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        VirtualThreads.executorService().use { executor ->
            val holder =
                executor.submit {
                    isolated.withPermit(lane) {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                }
            entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            assertFailsWith<DatabasePermitRejected> { isolated.withPermit(lane) { error("must not execute") } }
            val unaffected = if (lane == DatabaseLane.FOREGROUND) DatabaseLane.WORKER else DatabaseLane.FOREGROUND
            isolated.withPermit(unaffected) { "reserved-progress" } shouldBeEqualTo "reserved-progress"
            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
        }
        isolated.inUsePermits() shouldBeEqualTo 0
    }

    private fun exerciseLockTimeout(campaignId: java.util.UUID) {
        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            blocker.prepareStatement(
                "SELECT campaign_id FROM voucher_campaigns WHERE tenant_id = ? AND campaign_id = ? FOR UPDATE",
            ).use { statement ->
                statement.setString(1, TENANT_ID)
                statement.setObject(2, campaignId)
                statement.executeQuery().use { it.next().shouldBeTrue() }
            }
            configureCommandRuntime(lockTimeout = Duration.ofMillis(50))
            VirtualThreads.executorService().use { executor ->
                val failure =
                    executor.submit<Throwable?> {
                        runCatching {
                            allocation.allocate(AllocateVoucherCommand(TENANT_ID, campaignId, Base58.randomString(8)))
                        }.exceptionOrNull()
                    }.get(3, TimeUnit.SECONDS)
                (failure != null).shouldBeTrue()
            }
            blocker.rollback()
        }
        gate.inUsePermits() shouldBeEqualTo 0
    }

    private fun exerciseResponseLossReplay(campaignId: java.util.UUID) {
        val repository = HttpIdempotencyRepository(gate)
        val command =
            IdempotentVoucherCommand(
                scope =
                    IdempotencyScope(
                        tenantId = TENANT_ID,
                        principalDigest = Digest.sha256("principal"),
                        operation = "allocate",
                        resourceId = campaignId.toString(),
                        keyDigest = Digest.sha256("key"),
                    ),
                fingerprint = Digest.sha256("request"),
            )
        val response =
            StoredHttpResponse(
                responseKind = VoucherResponseKind.ALLOCATION_ACCEPTED,
                status = 201,
                headers = emptyMap(),
                aggregateId = campaignId,
                allocationId = Uuid.V7.nextId(),
                aggregateRevision = 0,
                generationKeyVersion = 5,
                verificationKeyVersion = 7,
            )
        var effects = 0
        val losingClient =
            IdempotentVoucherCommandService(repository, jdbc, clock) { point ->
                if (point == IdempotencyCutPoint.AFTER_COMMIT_BEFORE_RESPONSE) error("response lost")
            }
        assertFailsWith<IllegalStateException> {
            losingClient.execute(command, admission = { null }) { effects++; response }
        }
        val replay = IdempotentVoucherCommandService(repository, jdbc, clock).execute(command, admission = { null }) {
            effects++
            response
        }
        replay shouldBeEqualTo IdempotentCommandResult.Completed(response, replayed = true)
        effects shouldBeEqualTo 1
    }

    private fun exerciseWorkerIsolation() {
        val isolated = DatabasePermitGate(1, 1, 1, acquireTimeout = Duration.ofMillis(25))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        VirtualThreads.executorService().use { executor ->
            val poisoned =
                executor.submit<Throwable?> {
                    runCatching {
                        isolated.withPermit(DatabaseLane.WORKER) {
                            entered.countDown()
                            release.await(5, TimeUnit.SECONDS).shouldBeTrue()
                            error("poisoned worker item")
                        }
                    }.exceptionOrNull()
                }
            entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            isolated.withPermit(DatabaseLane.FOREGROUND) { "foreground-progress" } shouldBeEqualTo "foreground-progress"
            release.countDown()
            (poisoned.get(5, TimeUnit.SECONDS) is IllegalStateException).shouldBeTrue()
        }
        isolated.availablePermits(DatabaseLane.WORKER) shouldBeEqualTo 1
    }

    private fun exerciseKeyRotation(campaignId: java.util.UUID) {
        val originalRing =
            VoucherCodeKeyRing(
                currentGenerationVersion = 5,
                currentVerificationVersion = 7,
                generationKeys = mapOf(5 to ByteArray(32) { 0x15 }),
                verificationKeys = mapOf(7 to ByteArray(32) { 0x27 }),
            )
        val input = VoucherGenerationInput(TENANT_ID, campaignId, Uuid.V7.nextId())
        val issued = VoucherCodeService(originalRing).issue(input)
        val rotatedRing =
            originalRing.copy(
                currentVerificationVersion = 8,
                verificationKeys = originalRing.verificationKeys + (8 to ByteArray(32) { 0x28 }),
            )
        VoucherCodeService(rotatedRing).verify(issued.code, issued.verifier, 7).shouldBeTrue()
        VoucherCodeService(rotatedRing.copy(verificationKeys = rotatedRing.verificationKeys - 7))
            .verifyExternal(issued.code) shouldBeEqualTo VerificationResult.INVALID_CODE
    }

    private fun exerciseCancellation() {
        val isolated = DatabasePermitGate(1, 1, 1, acquireTimeout = Duration.ofSeconds(5))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        VirtualThreads.executorService().use { executor ->
            val holder =
                executor.submit {
                    isolated.withPermit(DatabaseLane.FOREGROUND) {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                }
            entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val cancelled =
                executor.submit<Boolean> {
                    Thread.currentThread().interrupt()
                    assertFailsWith<DatabasePermitRejected> {
                        isolated.withPermit(DatabaseLane.FOREGROUND) { error("must not execute") }
                    }
                    Thread.currentThread().isInterrupted
                }
            cancelled.get(5, TimeUnit.SECONDS).shouldBeTrue()
            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
        }
        isolated.availablePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
    }

    private fun exerciseShutdown() {
        val isolated = DatabasePermitGate(1, 1, 1)
        val coordinator =
            VoucherLifecycleCoordinator(
                gate = isolated,
                stopWorker = {},
                stopSse = {},
                releaseLeader = {},
                closeRedis = {},
                closeExecutor = {},
                graceDeadline = Duration.ofSeconds(1),
            )
        coordinator.shutdown()
        assertFailsWith<DatabasePermitRejected> {
            isolated.withPermit(DatabaseLane.FOREGROUND) { error("must not execute") }
        }
        isolated.inUsePermits() shouldBeEqualTo 0
    }

    private fun contributingClaims(campaignId: java.util.UUID): Long =
        queryLong(
            """
            SELECT count(*) FROM voucher_claims
             WHERE campaign_id = '$campaignId'
               AND capacity_reserved = true
               AND state IN ('ALLOCATED', 'REVIEW_REQUIRED', 'REDEEMED')
            """.trimIndent(),
        )

    private fun totalClaims(campaignId: java.util.UUID): Long =
        queryLong("SELECT count(*) FROM voucher_claims WHERE campaign_id = '$campaignId'")

    private class SequenceRateLimiter(vararg results: RateLimitResult) : RateLimiter<String> {
        private val results = ArrayDeque(results.toList())
        var calls = 0

        override fun consume(
            key: String,
            numToken: Long,
        ): RateLimitResult {
            calls++
            return results.removeFirst()
        }
    }

    private class LogCapture : AutoCloseable {
        private val logger = LoggerFactory.getLogger("io.bluetape4k.workshop.commerce.voucher") as Logger
        private val originalLevel = logger.level
        private val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }

        init {
            logger.level = Level.DEBUG
            logger.addAppender(appender)
        }

        val rendered: String
            get() = appender.list.joinToString("\n") { it.formattedMessage }

        override fun close() {
            logger.detachAppender(appender)
            logger.level = originalLevel
        }
    }

    private infix fun String.shouldNotContainSensitive(values: List<String>) {
        values.mapIndexedNotNull { index, sensitive -> index.takeIf { contains(sensitive) } } shouldBeEqualTo emptyList()
    }

    private companion object {
        fun consumed(): RateLimitResult = RateLimitResult.consumed(1, 99, RateLimitDiagnostics.EMPTY)

        fun failure(): RateLimitResult = RateLimitResult.error(IllegalStateException("redis timeout"))

        const val OPERATOR_SECRET = "local-operator-secret-0000000000000001"
    }
}
