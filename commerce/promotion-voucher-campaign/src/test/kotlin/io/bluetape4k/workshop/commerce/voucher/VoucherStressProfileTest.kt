package io.bluetape4k.workshop.commerce.voucher

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.bucket4j.ratelimit.RateLimitDiagnostics
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.voucher.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
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
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal enum class RedisMode {
    HEALTHY,
    TIMEOUT,
}

internal data class StressProfile(
    val concurrency: Int,
    val redisMode: RedisMode,
) {
    val id: String = "c${concurrency}-${redisMode.name.lowercase()}-hotspot"
}

internal class VoucherStressProfileTest : VoucherCommandTestSupport() {
    @Tag("stress")
    @ParameterizedTest(name = "{0}")
    @MethodSource("stressProfiles")
    fun `capacity hotspot records bounded evidence without a wall clock CI gate`(profile: StressProfile) {
        val reportRoot = Path.of(checkNotNull(System.getProperty("voucher.stress.report-directory")))
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
                    val evidence =
                        probe.finish(
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

    private fun runAllocations(
        profile: StressProfile,
        campaignId: java.util.UUID,
        redis: StressRedisHarness,
        probe: VoucherPerformanceProbe,
    ): List<AllocationResult> {
        val successes = ConcurrentLinkedQueue<AllocationResult>()
        runConcurrent(ALLOCATIONS, profile.concurrency) { index ->
            val started = System.nanoTime()
            val status =
                try {
                    when (redis.admission.decide("v1:${profile.id}:$index")) {
                        AdmissionDecision.Proceed -> {
                            successes +=
                                allocation.allocate(
                                    AllocateVoucherCommand(TENANT_ID, campaignId, "user-${Base58.randomString(8)}"),
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

    private fun runRedemptions(
        profile: StressProfile,
        allocations: List<AllocationResult>,
        probe: VoucherPerformanceProbe,
    ) {
        check(allocations.isNotEmpty()) { "stress profile must allocate at least one voucher" }
        runConcurrent(REDEMPTIONS, profile.concurrency) { index ->
            val allocation = allocations[index % allocations.size]
            val started = System.nanoTime()
            val status =
                try {
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

    private fun runConcurrent(
        operations: Int,
        concurrency: Int,
        action: (Int) -> Unit,
    ) {
        val permits = Semaphore(concurrency, true)
        VirtualThreads.executorService().use { executor ->
            val futures =
                (0 until operations).map { index ->
                    executor.submit {
                        permits.acquire()
                        try {
                            action(index)
                        } finally {
                            permits.release()
                        }
                    }
                }
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        }
    }

    private fun contributingClaims(campaignId: java.util.UUID): Long =
        queryLong(
            """
            SELECT count(*)
              FROM voucher_claims
             WHERE campaign_id = '$campaignId'
               AND capacity_reserved = true
               AND state IN ('ALLOCATED', 'REVIEW_REQUIRED', 'REDEEMED')
            """.trimIndent(),
        )

    private fun hikariDataSource(applicationName: String): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                poolName = applicationName
                jdbcUrl = compatibilityJdbcUrl()
                username = postgresUsername()
                password = postgresPassword()
                maximumPoolSize = 16
                minimumIdle = 0
                connectionTimeout = Duration.ofSeconds(15).toMillis()
                validationTimeout = Duration.ofSeconds(5).toMillis()
                addDataSourceProperty("ApplicationName", applicationName)
            },
        )

    private fun redisHarness(
        mode: RedisMode,
        probe: VoucherPerformanceProbe,
    ): StressRedisHarness {
        val uri = RedisURI.create(redis.url).apply { timeout = Duration.ofMillis(25) }
        val client = LettuceClients.clientOf(uri)
        val connection = LettuceClients.connect(client)
        val limiter =
            object : RateLimiter<String> {
                override fun consume(
                    key: String,
                    numToken: Long,
                ): RateLimitResult {
                    val started = System.nanoTime()
                    return try {
                        when (mode) {
                            RedisMode.HEALTHY -> {
                                connection.sync().ping()
                                RateLimitResult.consumed(numToken, Long.MAX_VALUE, RateLimitDiagnostics.EMPTY)
                            }

                            RedisMode.TIMEOUT -> {
                                connection.sync().blpop(1, "voucher:stress:$key")
                                RateLimitResult.consumed(numToken, Long.MAX_VALUE, RateLimitDiagnostics.EMPTY)
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

    private fun assertEvidenceSchema(
        jsonPath: Path,
        profile: StressProfile,
    ) {
        val root = Jackson.defaultJsonMapper.readTree(jsonPath.toFile())
        root.path("schemaVersion").stringValue() shouldBeEqualTo "1.0"
        root.path("profile").stringValue() shouldBeEqualTo profile.id
        root.path("environment").path("javaVersion").stringValue().isNotBlank().shouldBeTrue()
        root.path("operationCounts").path("allocation").intValue() shouldBeEqualTo ALLOCATIONS
        root.path("operationCounts").path("redemption").intValue() shouldBeEqualTo REDEMPTIONS
        root.path("latencyNanos").has("p95").shouldBeTrue()
        root.path("latencyNanos").has("p99").shouldBeTrue()
        (root.path("roundTrips").path("redisCommands").longValue() > 0L).shouldBeTrue()
        listOf("409", "429", "503").all(root.path("statusCounts")::has).shouldBeTrue()
        root.path("artifacts").path("jfr").stringValue().endsWith(".jfr").shouldBeTrue()
        root.path("probeSources").isArray.shouldBeTrue()
    }

    private class StressRedisHarness(
        val client: RedisClient,
        private val connection: StatefulRedisConnection<String, String>,
        val admission: VoucherAdmissionGate,
    ) : AutoCloseable {
        override fun close() {
            connection.close()
            LettuceClients.shutdown(client)
        }
    }

    companion object {
        private const val CAPACITY = 100
        private const val ALLOCATIONS = 500
        private const val REDEMPTIONS = 500
        private val redis: RedisServer = RedisServer.Launcher.redis
        private val expectedProfiles = stressProfiles().mapTo(sortedSetOf(), StressProfile::id)
        private val executedProfiles = java.util.concurrent.ConcurrentSkipListSet<String>()

        @JvmStatic
        fun stressProfiles(): List<StressProfile> =
            listOf(
                StressProfile(64, RedisMode.HEALTHY),
                StressProfile(64, RedisMode.TIMEOUT),
                StressProfile(128, RedisMode.HEALTHY),
                StressProfile(128, RedisMode.TIMEOUT),
            )

        @JvmStatic
        @AfterAll
        fun writeManifest() {
            expectedProfiles shouldBeEqualTo executedProfiles
            val reportRoot = Path.of(checkNotNull(System.getProperty("voucher.stress.report-directory")))
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
    }
}
