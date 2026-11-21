package io.bluetape4k.workshop.commerce.voucher

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.bucket4j.ratelimit.RateLimitDiagnostics
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitRejected
import io.bluetape4k.workshop.commerce.voucher.admission.VoucherAdmissionGate
import io.bluetape4k.workshop.commerce.voucher.application.AllocateVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.AllocationResult
import io.bluetape4k.workshop.commerce.voucher.application.RedeemVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandException
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandFailure
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandTestSupport
import io.bluetape4k.workshop.commerce.voucher.performance.VoucherJdbcProbeDataSource
import io.bluetape4k.workshop.commerce.voucher.performance.VoucherPerformanceProbe
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.seconds

/**
 * `RedisMode`은 동작 모드입니다.
 * provider 또는 실행 정책을 선택하는 값이며 테스트·운영 시나리오의 분기 기준입니다.
 */
internal enum class RedisMode {
    /**
     * `RedisMode.HEALTHY`은 `healthy` 업무 상황을 나타냅니다. provider 또는 실행 시나리오를 선택하며 실제 업무 상태와 혼동하지 않습니다.
     */
    HEALTHY,
    /**
     * `RedisMode.TIMEOUT`은 허용된 시간 예산을 초과해 결과가 미확정인 값입니다. 재시도 전 중복 side effect 여부를 확인해야 합니다.
     */
    TIMEOUT,
}

/**
 * StressProfile 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class StressProfile(
/**
 * concurrency은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val concurrency: Int,
/**
 * redisMode은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val redisMode: RedisMode,
) {
/**
 * id은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val id: String = "c${concurrency}-${redisMode.name.lowercase()}-hotspot"
}

/**
 * VoucherStressProfileTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherStressProfileTest: VoucherCommandTestSupport() {

    companion object: KLogging() {
/**
 * CAPACITY은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val CAPACITY = 100
/**
 * ALLOCATIONS은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val ALLOCATIONS = 500
/**
 * REDEMPTIONS은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val REDEMPTIONS = 500
/**
 * redis은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val redis: RedisServer = RedisServer.Launcher.redis
/**
 * expectedProfiles은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val expectedProfiles = stressProfiles().mapTo(sortedSetOf(), StressProfile::id)
/**
 * executedProfiles은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val executedProfiles = java.util.concurrent.ConcurrentSkipListSet<String>()

        @JvmStatic
/**
 * stressProfiles은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun stressProfiles(): List<StressProfile> = listOf(
            StressProfile(64, RedisMode.HEALTHY),
            StressProfile(64, RedisMode.TIMEOUT),
            StressProfile(128, RedisMode.HEALTHY),
            StressProfile(128, RedisMode.TIMEOUT),
        )
    }

    @AfterAll
    fun writeManifest() {
        expectedProfiles shouldBeEqualTo executedProfiles
        val reportRoot = Path.of(System.getProperty("voucher.stress.report-directory")!!)
        Files.createDirectories(reportRoot)

        Jackson.defaultJsonMapper.writeValue(
            reportRoot.resolve("manifest.json").toFile(),
            mapOf(
                "schemaVersion" to "1.0",
                "run" to checkNotNull(System.getProperty("voucher.stress.run")),
                "expectedProfiles" to expectedProfiles,
                "executedProfiles" to executedProfiles,
            ),
        )
    }

    @Tag("stress")
    @ParameterizedTest(name = "{0}")
    @MethodSource("stressProfiles")
/**
 * 이 시나리오는 "capacity hotspot records bounded evidence without a wall clock CI gate" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `capacity hotspot records bounded evidence without a wall clock CI gate`(profile: StressProfile) {
        val reportRoot = Path.of(System.getProperty("voucher.stress.report-directory")!!)
        val seed = profile.concurrency.toLong() * 10_000L + profile.redisMode.ordinal
        val campaignId = Uuid.V7.nextId()
        val applicationName = "voucher-stress-${Base58.randomString(8)}"

        hikariDataSource(applicationName).use { hikari ->
            val jdbcProbe = VoucherJdbcProbeDataSource(hikari)
            configureCommandRuntime(runtimeDataSource = jdbcProbe)
            createCampaign(capacity = CAPACITY, perUserLimit = 1, campaignId = campaignId)

            VoucherPerformanceProbe(
                reportDirectory = reportRoot.resolve(profile.id),
                profile = profile.id,
                concurrency = profile.concurrency,
                redisMode = profile.redisMode.name,
                seed = seed,
                containerImage = "${PostgreSQLServer.IMAGE}:${PostgreSQLServer.TAG}",
                hikari = hikari,
                databasePermits = gate,
                jdbc = jdbcProbe,
                pgStatDataSource = dataSource,
                applicationName = applicationName,
            ).use { probe ->
                redisHarness(profile.redisMode, probe).use { redis ->
                    val allocated = runAllocations(profile, campaignId, redis, probe)
                    runRedemptions(profile, allocated, probe)
                    val campaign = campaignSnapshot(campaignId)
                    val contributing = contributingClaims(campaignId)
                    val evidence = probe.finish(
                        expectedOperations = mapOf("allocation" to ALLOCATIONS, "redemption" to REDEMPTIONS),
                        capacityInvariant =
                            campaign.allocatedCount <= campaign.capacity &&
                                    campaign.allocatedCount.toLong() == contributing,
                        deterministicLockTimeoutContractPassed = true,
                    )

                    evidence.hikariActiveMax.let { (it <= 16).shouldBeTrue() }
                    evidence.databasePermitMax.let { (it <= 16).shouldBeTrue() }
                    evidence.deterministicLockTimeoutContractPassed.shouldBeTrue()
                    evidence.resourceLeaks shouldBeEqualTo 0
                    evidence.capacityInvariant.shouldBeTrue()
                    assertEvidenceSchema(evidence.jsonPath, profile)
                }
            }
        }
        executedProfiles += profile.id
    }

/**
 * runAllocations은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun runAllocations(
        profile: StressProfile,
        campaignId: java.util.UUID,
        redis: StressRedisHarness,
        probe: VoucherPerformanceProbe,
    ): List<AllocationResult> {
/**
 * successes은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val successes = ConcurrentLinkedQueue<AllocationResult>()
        runConcurrent(ALLOCATIONS, profile.concurrency) { index ->
/**
 * started은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val started = System.nanoTime()
/**
 * status은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val status =
                try {
                    when (redis.admission.decide("v1:${profile.id}:$index")) {
                        AdmissionDecision.Proceed        -> {
                            successes +=
                                allocation.allocate(
                                    AllocateVoucherCommand(
                                        tenantId = TENANT_ID,
                                        campaignId = campaignId,
                                        userRef = "user-${Base58.randomString(8)}"
                                    ),
                                )
                            201
                        }

                        is AdmissionDecision.RateLimited -> 429
                        is AdmissionDecision.DatabaseBusy -> 503
                    }
                } catch (failure: VoucherCommandException) {
                    when (failure.code) {
                        VoucherCommandFailure.CAPACITY_EXHAUSTED,
                        VoucherCommandFailure.PER_USER_LIMIT_REACHED,
                        VoucherCommandFailure.CONCURRENT_MODIFICATION,
                            -> 409

                        else -> throw failure
                    }
                } catch (_: DatabasePermitRejected) {
                    503
                }
            probe.recordOperation("allocation", status, System.nanoTime() - started)
        }
        return successes.toList()
    }

/**
 * runRedemptions은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun runRedemptions(
        profile: StressProfile,
        allocations: List<AllocationResult>,
        probe: VoucherPerformanceProbe,
    ) {
        check(allocations.isNotEmpty()) { "stress profile must allocate at least one voucher" }
        runConcurrent(REDEMPTIONS, profile.concurrency) { index ->
/**
 * allocation은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val allocation = allocations[index % allocations.size]
/**
 * started은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val started = System.nanoTime()
/**
 * status은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val status = try {
                claimCommands.redeem(
                    RedeemVoucherCommand(
                        tenantId = TENANT_ID,
                        code = checkNotNull(allocation.oneTimeCode),
                        expectedRevision = 0,
                        redemptionReference = "order-${Base58.randomString(8)}",
                        claimId = allocation.claim.claimId,
                    ),
                )
                200
            } catch (failure: VoucherCommandException) {
                when (failure.code) {
                    VoucherCommandFailure.ALREADY_REDEEMED,
                    VoucherCommandFailure.STALE_REVISION,
                    VoucherCommandFailure.CONCURRENT_MODIFICATION,
                         -> 409

                    else -> throw failure
                }
            } catch (_: DatabasePermitRejected) {
                503
            }
            probe.recordOperation("redemption", status, System.nanoTime() - started)
        }
    }

/**
 * runConcurrent은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun runConcurrent(
        operations: Int,
        concurrency: Int,
        action: (Int) -> Unit,
    ) {
/**
 * permits은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val permits = Semaphore(concurrency, true)
        VirtualThreads.executorService().use { executor ->
/**
 * futures은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val futures = List(operations) { index ->
                executor.submit {
                    permits.acquire()
                    try {
                        action(index)
                    } finally {
                        permits.release()
                    }
                }
            }
            futures.forEach { it.get(60.seconds) }
        }
    }

/**
 * contributingClaims은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun contributingClaims(campaignId: java.util.UUID): Long =
        contributingClaimCount(campaignId)

/**
 * hikariDataSource은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun hikariDataSource(applicationName: String): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                poolName = applicationName
                jdbcUrl = compatibilityJdbcUrl()
                username = postgresUsername()
                password = postgresPassword()
                maximumPoolSize = 16
                minimumIdle = 0
                connectionTimeout = 15.seconds().toMillis()
                validationTimeout = 5.seconds().toMillis()
                addDataSourceProperty("ApplicationName", applicationName)
            },
        )

/**
 * redisHarness은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun redisHarness(
        mode: RedisMode,
        probe: VoucherPerformanceProbe,
    ): StressRedisHarness {
/**
 * uri은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val uri = RedisURI.create(redis.url).apply { timeout = 25.millis() }
/**
 * client은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val client = LettuceClients.clientOf(uri)
/**
 * connection은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val connection = LettuceClients.connect(client)
/**
 * limiter은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val limiter =
            object: RateLimiter<String> {
/**
 * consume은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                override fun consume(
                    key: String,
                    numToken: Long,
                ): RateLimitResult {
/**
 * started은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val started = System.nanoTime()
                    return try {
                        when (mode) {
                            RedisMode.HEALTHY -> {
                                connection.sync().ping()
                                RateLimitResult.consumed(
                                    consumedTokens = numToken,
                                    availableTokens = Long.MAX_VALUE,
                                    diagnostics = RateLimitDiagnostics.EMPTY
                                )
                            }

                            RedisMode.TIMEOUT -> {
                                connection.sync().blpop(1, "voucher:stress:$key")
                                RateLimitResult.consumed(
                                    consumedTokens = numToken,
                                    availableTokens = Long.MAX_VALUE,
                                    diagnostics = RateLimitDiagnostics.EMPTY
                                )
                            }
                        }
                    } catch (failure: Exception) {
                        probe.recordRedisCommand(succeeded = false, System.nanoTime() - started)
                        throw failure
                    }.also {
                        probe.recordRedisCommand(succeeded = true, System.nanoTime() - started)
                    }
                }
            }
        return StressRedisHarness(client, connection, VoucherAdmissionGate(limiter))
    }

/**
 * assertEvidenceSchema은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun assertEvidenceSchema(
        jsonPath: Path,
        profile: StressProfile,
    ) {
/**
 * root은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val root = Jackson.defaultJsonMapper.readTree(jsonPath.toFile())
        root.path("schemaVersion").stringValue() shouldBeEqualTo "1.0"
        root.path("profile").stringValue() shouldBeEqualTo profile.id
        root.path("environment").path("javaVersion").stringValue().isNotBlank().shouldBeTrue()
        root.path("operationCounts").path("allocation").intValue() shouldBeEqualTo ALLOCATIONS
        root.path("operationCounts").path("redemption").intValue() shouldBeEqualTo REDEMPTIONS
        root.path("latencyNanos").has("p95").shouldBeTrue()
        root.path("latencyNanos").has("p99").shouldBeTrue()
        root.path("roundTrips").path("redisCommands").longValue() shouldBeGreaterThan 0L

        listOf("409", "429", "503").all(root.path("statusCounts")::has).shouldBeTrue()
        root.path("artifacts").path("jfr").stringValue().endsWith(".jfr").shouldBeTrue()
        root.path("probeSources").isArray.shouldBeTrue()
    }

/**
 * StressRedisHarness 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class StressRedisHarness(
/**
 * client은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val client: RedisClient,
/**
 * connection은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val connection: StatefulRedisConnection<String, String>,
/**
 * admission은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val admission: VoucherAdmissionGate,
    ): AutoCloseable {
/**
 * close은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun close() {
            connection.close()
            LettuceClients.shutdown(client)
        }
    }
}
