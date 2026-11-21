package io.bluetape4k.workshop.commerce.voucher

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.bucket4j.ratelimit.RateLimitDiagnostics
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
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
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/**
 * `FailureScenario`은 실패·오류 분류 코드입니다.
 * 업무 흐름의 허용된 분기를 고정하며 저장·이벤트·API 경계에서 동일하게 해석합니다.
 */
internal enum class FailureScenario {
    /**
     * `FailureScenario.REDIS_TIMEOUT_FLAPPING`은 허용된 시간 예산을 초과해 결과가 미확정인 값입니다. 재시도 전 중복 side effect
     * 여부를 확인해야 합니다.
     */
    REDIS_TIMEOUT_FLAPPING,
    /**
     * `FailureScenario.DATABASE_CONNECTION_EXHAUSTION`은 `database connection exhaustion` 업무 상황을
     * 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    DATABASE_CONNECTION_EXHAUSTION,
    /**
     * `FailureScenario.POSTGRESQL_LOCK_TIMEOUT`은 허용된 시간 예산을 초과해 결과가 미확정인 값입니다. 재시도 전 중복 side
     * effect 여부를 확인해야 합니다.
     */
    POSTGRESQL_LOCK_TIMEOUT,
    /**
     * `FailureScenario.RESPONSE_LOSS_REPLAY`은 `response loss replay` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    RESPONSE_LOSS_REPLAY,
    /**
     * `FailureScenario.CONTEXT_RESTART`은 `context restart` 업무 상황을 나타냅니다. 현재 aggregate의 revision과
     * 허용된 전이를 확인한 뒤 사용합니다.
     */
    CONTEXT_RESTART,
    /**
     * `FailureScenario.SLOW_SSE_CONSUMER`은 `slow sse consumer` 업무 상황을 나타냅니다. 현재 aggregate의
     * revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    SLOW_SSE_CONSUMER,
    /**
     * `FailureScenario.WORKER_POISON_STARVATION`은 `worker poison starvation` 업무 상황을 나타냅니다. 현재
     * aggregate의 revision과 허용된 전이를 확인한 뒤 사용합니다.
     */
    WORKER_POISON_STARVATION,
    /**
     * `FailureScenario.KEY_ROTATION`은 `key rotation` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    KEY_ROTATION,
    /**
     * `FailureScenario.CANCELLATION`은 `cancellation` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를
     * 확인한 뒤 사용합니다.
     */
    CANCELLATION,
    /**
     * `FailureScenario.SHUTDOWN`은 `shutdown` 업무 상황을 나타냅니다. 현재 aggregate의 revision과 허용된 전이를 확인한 뒤
     * 사용합니다.
     */
    SHUTDOWN,
}

/**
 * voucher campaign의 고객 command와 실패 경계를 하나의 PostgreSQL fixture에서
 * 반복 실행하는 end-to-end 통합 테스트입니다.
 *
 * 파라미터화한 [FailureScenario]마다 Redis flapping, lock/connection timeout, 응답
 * 유실 replay, context restart, SSE 지연, worker poison, key rotation, 취소와 종료를
 * 주입한 뒤 campaign capacity와 idempotency receipt가 원자적으로 보존되는지 봅니다.
 * 따라서 “HTTP가 200을 반환했다”가 아니라 재시도 후에도 voucher를 두 번 쓰지 않는다는
 * 업무 invariant를 증명합니다. 외부 Redis/KMS/provider의 실제 SLA는 포함하지 않습니다.
 */
internal class VoucherEndToEndIntegrationTest: VoucherCommandTestSupport() {

    private companion object: KLogging() {
/**
 * consumed은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun consumed(): RateLimitResult = RateLimitResult.consumed(
            consumedTokens = 1,
            availableTokens = 99,
            diagnostics = RateLimitDiagnostics.EMPTY
        )

/**
 * failure은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun failure(): RateLimitResult = RateLimitResult.error(IllegalStateException("redis timeout"))

/**
 * OPERATOR_SECRET은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val OPERATOR_SECRET = "local-operator-secret-0000000000000001"
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FailureScenario::class)
/**
 * 이 시나리오는 "every failure scenario preserves PostgreSQL capacity and idempotency" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `every failure scenario preserves PostgreSQL capacity and idempotency`(scenario: FailureScenario) {
        val campaignId = Uuid.V7.nextId()
        val userRef = Base58.randomString(8)
        createCampaign(capacity = 1, perUserLimit = 1, campaignId = campaignId)

        val capture = LogCapture()
        val allocationResult = capture.use {
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

/**
 * exercise은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * exerciseRedisFlapping은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun exerciseRedisFlapping() {
        val limiter = SequenceRateLimiter(failure(), consumed())
        val admission = VoucherAdmissionGate(
            rateLimiter = limiter,
            recoveryPolicy =
                AdmissionRecoveryPolicy(
                    failureThreshold = 1,
                    recoverySuccessThreshold = 1,
                    probeInterval = 1.nanos(),
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

/**
 * exerciseDatabaseExhaustion은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun exerciseDatabaseExhaustion(lane: DatabaseLane) {
        val isolated = DatabasePermitGate(1, 1, 1, acquireTimeout = 25.millis())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder = executor.submit {
                isolated.withPermit(lane) {
                    entered.countDown()
                    release.await(5.seconds).shouldBeTrue()
                }
            }
            entered.await(5.seconds).shouldBeTrue()

            assertFailsWith<DatabasePermitRejected> {
                isolated.withPermit(lane) {
                    error("must not execute")
                }
            }
            val unaffected = if (lane == DatabaseLane.FOREGROUND) DatabaseLane.WORKER else DatabaseLane.FOREGROUND

            isolated.withPermit(unaffected) { "reserved-progress" } shouldBeEqualTo "reserved-progress"
            release.countDown()
            holder.get(5.seconds)
        }
        isolated.inUsePermits() shouldBeEqualTo 0
    }

/**
 * exerciseLockTimeout은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun exerciseLockTimeout(campaignId: java.util.UUID) {
        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            val sql = "SELECT campaign_id FROM voucher_campaigns WHERE tenant_id = ? AND campaign_id = ? FOR UPDATE"
            blocker.prepareStatement(sql).use { statement ->
                statement.setString(1, TENANT_ID)
                statement.setObject(2, campaignId)
                statement.executeQuery().use {
                    it.next().shouldBeTrue()
                }
            }
            configureCommandRuntime(lockTimeout = 50.millis())
            VirtualThreads.executorService().use { executor ->
                val failure = executor.submit<Throwable?> {
                    runCatching {
                        allocation.allocate(
                            AllocateVoucherCommand(
                                tenantId = TENANT_ID,
                                campaignId = campaignId,
                                userRef = Base58.randomString(8)
                            )
                        )
                    }.exceptionOrNull()
                }.get(3.seconds)

                failure.shouldNotBeNull()
            }
            blocker.rollback()
        }
        gate.inUsePermits() shouldBeEqualTo 0
    }

/**
 * exerciseResponseLossReplay은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun exerciseResponseLossReplay(campaignId: java.util.UUID) {
        val repository = HttpIdempotencyRepository(gate)
        val command = IdempotentVoucherCommand(
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
        val response = StoredHttpResponse(
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
        val losingClient = IdempotentVoucherCommandService(
            idempotency = repository,
            transactions = jdbc,
            clock = clock
        ) { point ->
            if (point == IdempotencyCutPoint.AFTER_COMMIT_BEFORE_RESPONSE) {
                error("response lost")
            }
        }
        assertFailsWith<IllegalStateException> {
            losingClient.execute(command, admission = { null }) {
                effects++
                response
            }
        }
        val replay = IdempotentVoucherCommandService(
            idempotency = repository,
            transactions = jdbc,
            clock = clock
        ).execute(
            command,
            admission = { null }) {
            effects++
            response
        }
        replay shouldBeEqualTo IdempotentCommandResult.Completed(response, replayed = true)
        effects shouldBeEqualTo 1
    }

/**
 * exerciseWorkerIsolation은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun exerciseWorkerIsolation() {
        val isolated = DatabasePermitGate(1, 1, 1, acquireTimeout = 25.millis())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val poisoned = executor.submit<Throwable?> {
                runCatching {
                    isolated.withPermit(DatabaseLane.WORKER) {
                        entered.countDown()
                        release.await(5.seconds).shouldBeTrue()
                        error("poisoned worker item")
                    }
                }.exceptionOrNull()
            }
            entered.await(5.seconds).shouldBeTrue()

            isolated.withPermit(DatabaseLane.FOREGROUND) {
                "foreground-progress"
            } shouldBeEqualTo "foreground-progress"

            release.countDown()
            poisoned.get(5.seconds).shouldBeInstanceOf<IllegalStateException>()
        }
        isolated.availablePermits(DatabaseLane.WORKER) shouldBeEqualTo 1
    }

/**
 * exerciseKeyRotation은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun exerciseKeyRotation(campaignId: java.util.UUID) {
        val originalRing = VoucherCodeKeyRing(
            currentGenerationVersion = 5,
            currentVerificationVersion = 7,
            generationKeys = mapOf(5 to ByteArray(32) { 0x15 }),
            verificationKeys = mapOf(7 to ByteArray(32) { 0x27 }),
        )
        val input = VoucherGenerationInput(TENANT_ID, campaignId, Uuid.V7.nextId())
        val issued = VoucherCodeService(originalRing).issue(input)
        val rotatedRing = originalRing.copy(
            currentVerificationVersion = 8,
            verificationKeys = originalRing.verificationKeys + (8 to ByteArray(32) { 0x28 }),
        )
        VoucherCodeService(rotatedRing)
            .verify(
                candidate = issued.code,
                expectedVerifier = issued.verifier,
                verificationKeyVersion = 7
            )
            .shouldBeTrue()

        VoucherCodeService(rotatedRing.copy(verificationKeys = rotatedRing.verificationKeys - 7))
            .verifyExternal(issued.code) shouldBeEqualTo VerificationResult.INVALID_CODE
    }

/**
 * exerciseCancellation은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun exerciseCancellation() {
        val isolated = DatabasePermitGate(
            foregroundPermits = 1,
            workerPermits = 1,
            sseMaintenancePermits = 1,
            acquireTimeout = 5.seconds()
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        VirtualThreads.executorService().use { executor ->
            val holder = executor.submit {
                isolated.withPermit(DatabaseLane.FOREGROUND) {
                    entered.countDown()
                    release.await(5.seconds).shouldBeTrue()
                }
            }
            entered.await(5.seconds).shouldBeTrue()
            val cancelled = executor.submit<Boolean> {
                Thread.currentThread().interrupt()
                assertFailsWith<DatabasePermitRejected> {
                    isolated.withPermit(DatabaseLane.FOREGROUND) {
                        error("must not execute")
                    }
                }
                Thread.currentThread().isInterrupted
            }
            cancelled.get(5.seconds).shouldBeTrue()
            release.countDown()
            holder.get(5.seconds)
        }
        isolated.availablePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
    }

/**
 * exerciseShutdown은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun exerciseShutdown() {
        val isolated = DatabasePermitGate(1, 1, 1)
        val coordinator = VoucherLifecycleCoordinator(
            gate = isolated,
            stopWorker = {},
            stopSse = {},
            releaseLeader = {},
            closeRedis = {},
            closeExecutor = {},
            graceDeadline = 1.seconds(),
        )
        coordinator.shutdown()
        assertFailsWith<DatabasePermitRejected> {
            isolated.withPermit(DatabaseLane.FOREGROUND) {
                error("must not execute")
            }
        }
        isolated.inUsePermits() shouldBeEqualTo 0
    }

/**
 * contributingClaims은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun contributingClaims(campaignId: java.util.UUID): Long =
        contributingClaimCount(campaignId)

/**
 * totalClaims은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun totalClaims(campaignId: java.util.UUID): Long =
        totalClaimCount(campaignId)

/**
 * SequenceRateLimiter 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class SequenceRateLimiter(vararg results: RateLimitResult): RateLimiter<String> {
/**
 * results은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val results = ArrayDeque(results.toList())
/**
 * calls은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var calls = 0

/**
 * consume은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun consume(
            key: String,
            numToken: Long,
        ): RateLimitResult {
            calls++
            return results.removeFirst()
        }
    }

/**
 * LogCapture 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class LogCapture: AutoCloseable {
/**
 * logger은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val logger = LoggerFactory.getLogger("io.bluetape4k.workshop.commerce.voucher") as Logger
/**
 * originalLevel은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val originalLevel = logger.level
/**
 * appender은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }

        init {
            logger.level = Level.DEBUG
            logger.addAppender(appender)
        }

/**
 * rendered은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val rendered: String
            get() = appender.list.joinToString("\n") { it.formattedMessage }

/**
 * close은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun close() {
            logger.detachAppender(appender)
            logger.level = originalLevel
        }
    }

/**
 * shouldNotContainSensitive은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private infix fun String.shouldNotContainSensitive(values: List<String>) {
        values.mapIndexedNotNull { index, sensitive ->
            index.takeIf { contains(sensitive) }
        }.shouldBeEmpty()
    }
}
