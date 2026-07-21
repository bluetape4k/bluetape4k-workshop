@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.performance

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionLimits
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionNamespace
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.admission.PermitLane
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionGate
import io.bluetape4k.workshop.commerce.voucherpool.application.AllocateVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchSourceKind
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateCampaignCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateImportBatchCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ImportChunkCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcAllocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcCampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcRedemptionService
import io.bluetape4k.workshop.commerce.voucherpool.application.JdbcReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReserveVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.applied
import io.bluetape4k.workshop.commerce.voucherpool.config.RecoverableVoucherPoolAdmissionBackend
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMetrics
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolRedisProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolRedisResources
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolSseProperties
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.JdbcVoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcTimeoutPhase
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcVoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcMetrics
import io.bluetape4k.workshop.commerce.voucherpool.query.JdbcVoucherPoolQueryService
import io.bluetape4k.workshop.commerce.voucherpool.query.JdbcVoucherPoolQueryStore
import io.bluetape4k.workshop.commerce.voucherpool.security.AesGcmVoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKey
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKeyRing
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorage
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKek
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import io.bluetape4k.workshop.commerce.voucherpool.web.PostgresVoucherPoolEventSource
import io.bluetape4k.workshop.commerce.voucherpool.web.ReserveVoucherRequest
import io.bluetape4k.workshop.commerce.voucherpool.web.TenantPrincipal
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolApiException
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolEventStream
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolHttpProperties
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolHttpCommandExecutor
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkerRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkers
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunRequest
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerRunState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.core.io.ClassPathResource
import java.io.PrintWriter
import java.io.Serializable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

internal enum class RedisMode {
    HEALTHY,
    UNAVAILABLE,
}

internal data class VoucherPoolStressEvidence(
    val runId: String,
    val clients: Int,
    val redisMode: RedisMode,
    val winners: Int,
    val successfulResponses: Int,
    val authoritativeReservationCount: Int,
    val authoritativeAllocationCount: Int,
    val duplicateWinnerCount: Int,
    val stateCountSum: Int,
    val entryCount: Int,
    val hikariActiveMax: Int,
    val hikariAcquisitionWaitMaxMillis: Long,
    val totalPermitHoldersMax: Int,
    val foregroundWaitMaxMillis: Long,
    val workerWaitMaxMillis: Long,
    val sseWaitMaxMillis: Long,
    val acquisitionTimeouts: Int,
    val acquisitionTimeoutTerminalDescriptors: Int,
    val deadlineViolations: Int,
    val hikariPendingMax: Int,
    val hikariPendingDrainMillis: Long,
    val workerCheckpointProgress: Long,
    val connectionLeaks: Int,
    val permitLeaks: Int,
    val counterDrift: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class HikariTimeoutEvidence(
    val status: Int,
    val code: String,
    val retryable: Boolean,
    val ownerReleased: Boolean,
    val terminalDescriptorWritten: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private data class StressProfileRun(
    val evidence: VoucherPoolStressEvidence,
    val threadDump: String,
)

@Suppress("LongMethod", "LongParameterList", "NestedBlockDepth", "TooManyFunctions")
internal class VoucherPoolPerformanceProbe(
    private val postgres: PostgreSQLServer,
    private val outputRoot: Path,
    private val redis: RedisServer = RedisServer.Launcher.redis,
) {
    fun runProfile(
        runId: String,
        entries: Int,
        clients: Int,
        sameUserPercent: Int,
        redis: RedisMode,
        workerLoad: Boolean,
        sseSubscribers: Int,
        freshDatabase: Boolean,
    ): VoucherPoolStressEvidence {
        require(entries > clients)
        require(sameUserPercent in 0..100)
        require(freshDatabase) { "stress evidence requires a fresh database schema" }
        val profile = "${clients}-${redis.name.lowercase()}"
        val schema = schemaName(runId, profile)
        createSchema(schema)
        val dataSource = dataSource(schema, profile)
        var harness: ActualVoucherPoolStressHarness? = null
        return try {
            migrate(dataSource)
            harness = ActualVoucherPoolStressHarness(dataSource, this.redis, profile, clients, redis)
            harness.createPool(entries)
            prewarm(dataSource)
            val result = harness.exercise(
                runId = runId,
                entries = entries,
                clients = clients,
                sameUserPercent = sameUserPercent,
                workerLoad = workerLoad,
                sseSubscribers = sseSubscribers,
            )
            StressEvidenceWriter(outputRoot).write(result.evidence, result.threadDump)
            result.evidence
        } finally {
            harness?.close()
            dataSource.close()
            dropSchema(schema)
        }
    }

    fun acquisitionTimeoutSemantics(): HikariTimeoutEvidence {
        val schema = schemaName("timeout", System.nanoTime().toString())
        createSchema(schema)
        val dataSource = dataSource(
            schema = schema,
            profile = "timeout",
            maximumPoolSize = TIMEOUT_POOL_SIZE,
            connectionTimeoutMillis = TIMEOUT_ACQUISITION_MILLIS,
        )
        return try {
            migrate(dataSource)
            ActualVoucherPoolStressHarness(
                dataSource = dataSource,
                redisServer = redis,
                profile = "timeout-fixture",
                clients = 1,
                redisMode = RedisMode.UNAVAILABLE,
            ).use { it.createPool(TIMEOUT_FIXTURE_ENTRIES) }

            val blockingDataSource = OneShotAcquisitionTimeoutDataSource(dataSource, TIMEOUT_POOL_SIZE)
            val metrics = RecordingJdbcMetrics()
            val graph = ServiceGraph(blockingDataSource, DatabasePermitGate.default(HIKARI_POOL_SIZE), metrics)
            val failure = runCatching {
                graph.http.reserve(
                    TenantPrincipal(graph.tenant, graph.principal),
                    timeoutCampaignId(),
                    TIMEOUT_IDEMPOTENCY_KEY,
                    "*",
                    ReserveVoucherRequest(),
                    "timeout-request",
                )
            }.exceptionOrNull()
            blockingDataSource.awaitHolder()
            check(failure is VoucherPoolApiException) { "HTTP command must translate the JDBC acquisition timeout" }
            check(metrics.acquisitionTimeouts.get() >= 2) {
                "the command and at least one owner-release attempt must observe acquisition timeouts"
            }
            val owner = timeoutOwnerState(dataSource)
            HikariTimeoutEvidence(
                status = failure.status,
                code = failure.stableCode,
                retryable = failure.retryAfterSeconds != null,
                ownerReleased = owner.status == "RETRYABLE_FAILED" && owner.ownerTokenDigest == null,
                terminalDescriptorWritten = owner.descriptor != null,
            )
        } finally {
            dataSource.close()
            dropSchema(schema)
        }
    }

    private fun timeoutOwnerState(dataSource: HikariDataSource): TimeoutOwnerState =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT status,owner_token_digest,descriptor::text FROM voucher_pool_http_idempotency " +
                    "WHERE tenant_id=? AND operation='voucher-reserve'",
            ).use { statement ->
                statement.setString(1, TIMEOUT_TENANT)
                statement.executeQuery().use { result ->
                    check(result.next()) { "actual idempotency owner row must exist" }
                    TimeoutOwnerState(result.getString(1), result.getBytes(2), result.getString(3))
                }
            }
        }

    private fun migrate(dataSource: HikariDataSource) {
        VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            MIGRATION_LOCK,
        ).migrate()
    }

    private fun prewarm(dataSource: HikariDataSource) {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            List(dataSource.maximumPoolSize) {
                executor.submit(Callable { dataSource.connection.use { connection -> connection.isValid(2) } })
            }.forEach { check(it.get()) }
        }
    }

    private fun dataSource(
        schema: String,
        profile: String,
        maximumPoolSize: Int = HIKARI_POOL_SIZE,
        connectionTimeoutMillis: Long = HIKARI_ACQUISITION_DEADLINE_MILLIS,
    ): HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username ?: PostgreSQLServer.USERNAME
            password = postgres.password ?: PostgreSQLServer.PASSWORD
            this.maximumPoolSize = maximumPoolSize
            minimumIdle = maximumPoolSize
            connectionTimeout = connectionTimeoutMillis
            poolName = "voucher-stress-$profile"
            connectionInitSql = "SET search_path TO $schema"
        },
    )

    private fun createSchema(schema: String) = adminConnection().use { connection ->
        connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE; CREATE SCHEMA $schema") }
    }

    private fun dropSchema(schema: String) = adminConnection().use { connection ->
        connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    private fun adminConnection(): Connection = java.sql.DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username ?: PostgreSQLServer.USERNAME,
        postgres.password ?: PostgreSQLServer.PASSWORD,
    )

    private fun schemaName(runId: String, profile: String): String =
        "stress_${(runId + profile).replace(Regex("[^A-Za-z0-9]"), "_").lowercase()}_${System.nanoTime()}"

    private data class TimeoutOwnerState(
        val status: String,
        val ownerTokenDigest: ByteArray?,
        val descriptor: String?,
    )

    companion object {
        private const val HIKARI_POOL_SIZE = 16
        private const val HIKARI_ACQUISITION_DEADLINE_MILLIS = 2_000L
        private const val TIMEOUT_ACQUISITION_MILLIS = 250L
        private const val TIMEOUT_POOL_SIZE = 4
        private const val TIMEOUT_FIXTURE_ENTRIES = 10
        private const val MIGRATION_LOCK = 537_013L
        private const val TIMEOUT_IDEMPOTENCY_KEY = "stress-timeout-reserve"
        private const val TIMEOUT_TENANT = "tenant-timeout-fixture"

        private fun timeoutCampaignId(): UUID =
            UUID.nameUUIDFromBytes("timeout-fixture-campaign".toByteArray(StandardCharsets.UTF_8))
    }
}

@Suppress("LongMethod", "LongParameterList", "TooManyFunctions")
private class ActualVoucherPoolStressHarness(
    private val dataSource: HikariDataSource,
    redisServer: RedisServer,
    private val profile: String,
    private val clients: Int,
    redisMode: RedisMode,
) : AutoCloseable {
    val tenant = "tenant-$profile"
    val campaignId: UUID = UUID.nameUUIDFromBytes("$profile-campaign".toByteArray(StandardCharsets.UTF_8))
    val batchId: UUID = UUID.nameUUIDFromBytes("$profile-batch".toByteArray(StandardCharsets.UTF_8))
    private val metrics = RecordingJdbcMetrics()
    private val gate = DatabasePermitGate.default(HIKARI_POOL_SIZE)
    private val graph = ServiceGraph(dataSource, gate, metrics, tenant, campaignId)
    private val redisProperties = redisProperties(redisServer, clients, redisMode)
    private val redisResources = openRedisResources(redisServer, redisProperties, redisMode)
    private val admission =
        VoucherPoolAdmissionGate(
            backend = RecoverableVoucherPoolAdmissionBackend(redisResources.client, redisProperties),
            limits = redisProperties.limits,
        )
    private val properties = stressProperties(redisProperties)
    private val http = graph.http(admission)
    private val eventSource = PostgresVoucherPoolEventSource(graph.executor, graph.digests, JSON, properties)
    private val workers = JdbcVoucherPoolWorkers(graph.executor, graph.workerClaims, graph.repository)

    fun createPool(entries: Int) {
        val manifest = digest("manifest-$profile-$entries")
        val firstChunkSize = minOf(CHUNK_SIZE, entries)
        val firstCodes = codes(0, firstChunkSize)
        val campaign = graph.commands.createCampaign(
            CreateCampaignCommand(
                tenantId = tenant,
                campaignId = campaignId,
                startsAt = Instant.now().minusSeconds(60),
                endsAt = Instant.now().plusSeconds(3_600),
                policy = VoucherPoolPolicy.of(clients, 5.minutes, 10.minutes, 1),
                idempotencyKey = key("create-campaign"),
            ),
        ).applied()
        graph.commands.activateCampaign(
            CampaignRevisionCommand(tenant, campaignId, campaign.revision, key("activate-campaign")),
        ).applied()
        var batch = graph.commands.createImportBatch(
            CreateImportBatchCommand(
                tenantId = tenant,
                batchId = batchId,
                campaignId = campaignId,
                sourceKind = BatchSourceKind.IMPORTED,
                manifestDigest = manifest,
                requestFingerprint = digest("request-$profile-$entries"),
                expectedCount = entries.toLong(),
                activatesAt = Instant.now().minusSeconds(1),
                initialCodes = firstCodes,
                idempotencyKey = key("create-batch"),
            ),
        ).applied()
        var firstOrdinal = firstChunkSize
        while (firstOrdinal < entries) {
            val count = minOf(CHUNK_SIZE, entries - firstOrdinal)
            batch = graph.commands.importChunk(
                ImportChunkCommand(
                    tenantId = tenant,
                    batchId = batchId,
                    campaignId = campaignId,
                    firstOrdinal = firstOrdinal.toLong(),
                    manifestDigest = manifest,
                    codes = codes(firstOrdinal, count),
                    expectedRevision = batch.revision,
                    idempotencyKey = key("chunk-$firstOrdinal"),
                ),
            ).applied()
            firstOrdinal += count
        }
        graph.commands.activateBatch(
            BatchRevisionCommand(tenant, campaignId, batchId, batch.revision, key("activate-batch")),
        ).applied()
    }

    fun exercise(
        runId: String,
        entries: Int,
        clients: Int,
        sameUserPercent: Int,
        workerLoad: Boolean,
        sseSubscribers: Int,
    ): StressProfileRun {
        val samplerRunning = AtomicBoolean(true)
        val permitHoldersMax = AtomicInteger()
        val hikariActiveMax = AtomicInteger()
        val hikariPendingMax = AtomicInteger()
        val start = CountDownLatch(1)
        val loadStarted = CountDownLatch(clients + sseSubscribers + if (workerLoad) 1 else 0)
        val loadThreads = ConcurrentHashMap.newKeySet<Thread>()
        val subscriptions = ConcurrentLinkedQueue<AutoCloseable>()
        val deadlineViolations = AtomicInteger()
        val winners = ConcurrentHashMap.newKeySet<UUID>()

        val eventExecutor = Executors.newVirtualThreadPerTaskExecutor()
        val stream = VoucherPoolEventStream(
            eventSource,
            JSON,
            eventExecutor,
            properties,
            VoucherPoolMetrics(SimpleMeterRegistry()),
        )
        val loadExecutor = Executors.newVirtualThreadPerTaskExecutor()
        try {
                    val sampler = loadExecutor.submit {
                        while (samplerRunning.get()) {
                            val snapshot = gate.snapshot()
                            permitHoldersMax.accumulateAndGet(
                                snapshot.foregroundInUse + snapshot.workerInUse + snapshot.sseInUse,
                                ::maxOf,
                            )
                            hikariActiveMax.accumulateAndGet(dataSource.hikariPoolMXBean.activeConnections, ::maxOf)
                            hikariPendingMax.accumulateAndGet(dataSource.hikariPoolMXBean.threadsAwaitingConnection, ::maxOf)
                            Thread.sleep(SAMPLE_INTERVAL_MILLIS)
                        }
                    }
                    val clientFutures = List(clients) { index ->
                        loadExecutor.submit(Callable {
                            start.await()
                            loadThreads += Thread.currentThread()
                            loadStarted.countDown()
                            val user = if (index * 100 / clients < sameUserPercent) SHARED_USER else "user-$index"
                            val principal = TenantPrincipal(tenant, user)
                            val reservation = retryableHttp {
                                checkNotNull(
                                    http.reserve(
                                        principal,
                                        campaignId,
                                        key("reserve-$index"),
                                        "*",
                                        ReserveVoucherRequest(),
                                        "reserve-request-$index",
                                    ).body,
                                )
                            }
                            val allocation = retryableHttp {
                                checkNotNull(
                                    http.allocate(
                                        principal,
                                        reservation.reservationId,
                                        key("allocate-$index"),
                                        "\"${reservation.revision}\"",
                                        "allocate-request-$index",
                                    ).body,
                                )
                            }
                            allocation.allocationId
                        })
                    }
                    val workerFuture = if (workerLoad) {
                        loadExecutor.submit(Callable {
                            start.await()
                            loadThreads += Thread.currentThread()
                            loadStarted.countDown()
                            val outcome = workers.run(
                                WorkerRunRequest(
                                    tenantId = tenant,
                                    kind = WorkerKind.RECONCILIATION,
                                    scopeId = batchId,
                                    owner = "stress-worker-$profile",
                                    requestedLimit = WORKER_CHUNK_SIZE,
                                ),
                            )
                            check(outcome.state == WorkerRunState.COMPLETED) { "actual worker must complete" }
                            checkNotNull(outcome.claim).checkpoint
                        })
                    } else {
                        null
                    }
                    val sseFutures = List(sseSubscribers) { index ->
                        loadExecutor.submit(Callable {
                            start.await()
                            loadThreads += Thread.currentThread()
                            loadStarted.countDown()
                            stream.openCustomer(tenant, "sse-user-$index", null).also { subscription ->
                                check(checkNotNull(subscription.next(Duration.ZERO)).type == "snapshot")
                                subscriptions += subscription
                            }
                        })
                    }

                    start.countDown()
                    check(loadStarted.await(LOAD_START_SECONDS, TimeUnit.SECONDS)) {
                        "all production-path stress tasks must start before evidence capture"
                    }
                    val underLoadThreadDump = stressThreadDump(profile, loadThreads)
                    val outcomes = clientFutures.map { future ->
                        getBeforeDeadline(future, deadlineViolations)
                    }
                    winners += authoritativeWinnerEntryIds()
                    val workerCheckpoint = workerFuture?.let { getBeforeDeadline(it, deadlineViolations) } ?: 0L
                    sseFutures.forEach { getBeforeDeadline(it, deadlineViolations) }
                    Thread.sleep(SSE_ACTIVE_MILLIS)
                    subscriptions.forEach(AutoCloseable::close)
                    stream.close()
                    samplerRunning.set(false)
                    sampler.get(SAMPLER_STOP_SECONDS, TimeUnit.SECONDS)

                    val drainStarted = System.nanoTime()
                    while (
                        dataSource.hikariPoolMXBean.threadsAwaitingConnection > 0 ||
                        dataSource.hikariPoolMXBean.activeConnections > 0
                    ) {
                        if (elapsedMillis(drainStarted) > HIKARI_DRAIN_DEADLINE_MILLIS) {
                            deadlineViolations.incrementAndGet()
                            break
                        }
                        Thread.sleep(DRAIN_POLL_MILLIS)
                    }
                    val drainMillis = elapsedMillis(drainStarted)
                    val counts = authoritativeCounts()
                    val permitSnapshot = gate.snapshot()
                    val permitLeaks = permitSnapshot.foregroundInUse + permitSnapshot.workerInUse + permitSnapshot.sseInUse
                    return StressProfileRun(
                        evidence = VoucherPoolStressEvidence(
                            runId = runId,
                            clients = clients,
                            redisMode = redisMode(),
                            winners = winners.size,
                            successfulResponses = outcomes.size,
                            authoritativeReservationCount = counts.reservations,
                            authoritativeAllocationCount = counts.allocations,
                            duplicateWinnerCount = outcomes.size - winners.size,
                            stateCountSum = counts.stateCountSum,
                            entryCount = entries,
                            hikariActiveMax = hikariActiveMax.get(),
                            hikariAcquisitionWaitMaxMillis = dataSource.connectionTimeout,
                            totalPermitHoldersMax = permitHoldersMax.get(),
                            foregroundWaitMaxMillis = permitSnapshot.waits.getValue(PermitLane.FOREGROUND).inWholeMilliseconds,
                            workerWaitMaxMillis = permitSnapshot.waits.getValue(PermitLane.WORKER).inWholeMilliseconds,
                            sseWaitMaxMillis = permitSnapshot.waits.getValue(PermitLane.SSE).inWholeMilliseconds,
                            acquisitionTimeouts = metrics.acquisitionTimeouts.get(),
                            acquisitionTimeoutTerminalDescriptors = counts.acquisitionTimeoutTerminalDescriptors,
                            deadlineViolations = deadlineViolations.get() + metrics.deadlineTimeouts.get(),
                            hikariPendingMax = hikariPendingMax.get(),
                            hikariPendingDrainMillis = drainMillis,
                            workerCheckpointProgress = workerCheckpoint,
                            connectionLeaks = dataSource.hikariPoolMXBean.activeConnections,
                            permitLeaks = permitLeaks,
                            counterDrift = counts.counterDrift,
                        ),
                        threadDump = underLoadThreadDump,
                    )
        } finally {
            samplerRunning.set(false)
            loadExecutor.shutdownNow()
            subscriptions.forEach(AutoCloseable::close)
            stream.close()
            eventExecutor.shutdownNow()
        }
    }

    private fun authoritativeCounts(): AuthoritativeCounts = dataSource.connection.use { connection ->
        val reservations = connection.scalar("SELECT count(*) FROM voucher_pool_reservations WHERE tenant_id=?", tenant)
        val allocations = connection.scalar("SELECT count(*) FROM voucher_pool_allocations WHERE tenant_id=?", tenant)
        val stateCountSum = connection.scalar("SELECT count(*) FROM voucher_pool_entries WHERE tenant_id=?", tenant)
        val activeAllocations = connection.scalar(
            "SELECT coalesce(sum(active_allocations),0) FROM voucher_pool_user_limits WHERE tenant_id=?",
            tenant,
        )
        val poolDepth = connection.scalar(
            "SELECT coalesce(sum(entry_count),0) FROM voucher_pool_pool_depth WHERE tenant_id=?",
            tenant,
        )
        val timeoutDescriptors = connection.scalar(
            "SELECT count(*) FROM voucher_pool_http_idempotency WHERE tenant_id=? " +
                "AND descriptor::text LIKE '%BACKEND_TIMEOUT%'",
            tenant,
        )
        AuthoritativeCounts(
            reservations = reservations.toInt(),
            allocations = allocations.toInt(),
            stateCountSum = stateCountSum.toInt(),
            acquisitionTimeoutTerminalDescriptors = timeoutDescriptors.toInt(),
            counterDrift = kotlin.math.abs(activeAllocations - allocations) + kotlin.math.abs(poolDepth - stateCountSum),
        )
    }

    private fun authoritativeWinnerEntryIds(): Set<UUID> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT entry_id FROM voucher_pool_allocations WHERE tenant_id=?",
        ).use { statement ->
            statement.setString(1, tenant)
            statement.executeQuery().use { result ->
                buildSet { while (result.next()) add(result.getObject(1, UUID::class.java)) }
            }
        }
    }

    private fun Connection.scalar(sql: String, tenantId: String): Long =
        prepareStatement(sql).use { statement ->
            statement.setString(1, tenantId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getLong(1)
            }
        }

    private fun codes(firstOrdinal: Int, count: Int): List<String> =
        List(count) { offset -> "VP-$profile-${firstOrdinal + offset}" }

    private fun key(suffix: String): String = "stress-$profile-$suffix"

    private fun <T> retryableHttp(block: () -> T): T {
        val startedAt = System.nanoTime()
        while (true) {
            try {
                return block()
            } catch (failure: VoucherPoolApiException) {
                if (failure.stableCode !in RETRYABLE_HTTP_CODES || elapsedMillis(startedAt) >= PROFILE_DEADLINE_MILLIS) {
                    throw failure
                }
                Thread.sleep(RETRY_BACKOFF_MILLIS)
            }
        }
    }

    private fun digest(value: String): DigestValue =
        DigestValue.of(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun redisMode(): RedisMode =
        if (redisProperties.uri.endsWith(UNAVAILABLE_REDIS_PORT)) RedisMode.UNAVAILABLE else RedisMode.HEALTHY

    override fun close() {
        redisResources.close()
    }

    private data class AuthoritativeCounts(
        val reservations: Int,
        val allocations: Int,
        val stateCountSum: Int,
        val acquisitionTimeoutTerminalDescriptors: Int,
        val counterDrift: Long,
    )

    companion object {
        private const val HIKARI_POOL_SIZE = 16
        private const val CHUNK_SIZE = 500
        private const val WORKER_CHUNK_SIZE = 50
        private const val SAMPLE_INTERVAL_MILLIS = 2L
        private const val SSE_ACTIVE_MILLIS = 300L
        private const val DRAIN_POLL_MILLIS = 5L
        private const val HIKARI_DRAIN_DEADLINE_MILLIS = 12_000L
        private const val PROFILE_DEADLINE_SECONDS = 30L
        private const val PROFILE_DEADLINE_MILLIS = PROFILE_DEADLINE_SECONDS * 1_000L
        private const val LOAD_START_SECONDS = 5L
        private const val SAMPLER_STOP_SECONDS = 2L
        private const val RETRY_BACKOFF_MILLIS = 10L
        private const val SHARED_USER = "same-user"
        private const val UNAVAILABLE_REDIS_PORT = ":1"
        private val RETRYABLE_HTTP_CODES =
            setOf("BACKEND_TIMEOUT", "POOL_BUSY", "RATE_LIMITED", "COMMAND_IN_PROGRESS")
        private val JSON = Jackson.defaultJsonMapper

        private fun <T> getBeforeDeadline(
            future: java.util.concurrent.Future<T>,
            deadlineViolations: AtomicInteger,
        ): T = try {
            future.get(PROFILE_DEADLINE_SECONDS, TimeUnit.SECONDS)
        } catch (failure: java.util.concurrent.TimeoutException) {
            deadlineViolations.incrementAndGet()
            throw failure
        }

        private fun elapsedMillis(startedAt: Long): Long =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        private fun stressLimits(@Suppress("UNUSED_PARAMETER") clients: Int): AdmissionLimits {
            var limits = AdmissionLimits.defaults()
            AdmissionNamespace.entries.forEach { namespace ->
                limits = limits.withLimit(namespace, STRESS_ADMISSION_LIMIT)
            }
            return limits
        }

        private const val STRESS_ADMISSION_LIMIT = 10_000

        private fun redisProperties(
            redisServer: RedisServer,
            clients: Int,
            mode: RedisMode,
        ): VoucherPoolRedisProperties =
            VoucherPoolRedisProperties(
                enabled = true,
                uri = if (mode == RedisMode.HEALTHY) redisServer.url else "redis://127.0.0.1:1",
                commandTimeout = Duration.ofMillis(if (mode == RedisMode.HEALTHY) 500 else 50),
                limits = stressLimits(clients),
            )

        private fun openRedisResources(
            redisServer: RedisServer,
            properties: VoucherPoolRedisProperties,
            mode: RedisMode,
        ): VoucherPoolRedisResources {
            if (mode == RedisMode.HEALTHY) {
                val client = LettuceClients.clientOf(redisServer.url)
                try {
                    LettuceClients.connect(client).use { it.sync().flushall() }
                } finally {
                    LettuceClients.shutdown(client)
                }
            }
            return VoucherPoolRedisResources.open(properties)
        }

        private fun stressProperties(redis: VoucherPoolRedisProperties): VoucherPoolProperties =
            VoucherPoolProperties(
                redis = redis,
                sse = VoucherPoolSseProperties(
                    pollInterval = Duration.ofMillis(50),
                    maxIdleInterval = Duration.ofMillis(100),
                    heartbeatInterval = Duration.ofSeconds(1),
                ),
                http = VoucherPoolHttpProperties(
                    operatorSecret = "stress-operator-secret-0000000000000001",
                    operatorGuard = "stress-operator-guard",
                ),
            )
    }
}

@Suppress("LongParameterList")
private class ServiceGraph(
    dataSource: DataSource,
    gate: DatabasePermitGate,
    metrics: RecordingJdbcMetrics,
    val tenant: String = TIMEOUT_TENANT,
    val campaignId: UUID = TIMEOUT_CAMPAIGN_ID,
) {
    val principal = "timeout-principal"
    val executor = VoucherPoolJdbcExecutor(
        gate,
        SpringTransactionManager(dataSource, DatabaseConfig {}, false),
        metrics = metrics,
    )
    val repository = JdbcVoucherPoolRepository(dataSource)
    val digests = digestService()
    private val idempotency = JdbcVoucherPoolIdempotencyRepository(digests)
    private val crypto = AesGcmVoucherEnvelopeCrypto(
        VoucherKekRing.of(VoucherKek.of("stress-test-kek", keyBytes(11))),
        digests,
    )
    val commands = JdbcCampaignBatchCommandService(executor, repository, idempotency, digests, crypto)
    private val reservations = JdbcReservationService(executor, repository, idempotency, digests)
    private val allocations = JdbcAllocationService(
        executor,
        repository,
        idempotency,
        digests,
        VoucherCryptoStorage(repository, crypto),
    )
    private val redemptions = JdbcRedemptionService(executor, repository, idempotency, digests)
    val queries = JdbcVoucherPoolQueryService(executor, JdbcVoucherPoolQueryStore(), digests)
    val workerClaims = JdbcVoucherPoolWorkerRepository(executor)
    val http = http(VoucherPoolAdmissionGate(null, stressLimits()))

    fun http(admission: VoucherPoolAdmissionGate): VoucherPoolHttpCommandExecutor =
        VoucherPoolHttpCommandExecutor(reservations, allocations, redemptions, queries, admission, digests)

    companion object {
        private const val TIMEOUT_TENANT = "tenant-timeout-fixture"
        private val TIMEOUT_CAMPAIGN_ID =
            UUID.nameUUIDFromBytes("timeout-fixture-campaign".toByteArray(StandardCharsets.UTF_8))

        private fun stressLimits(): AdmissionLimits {
            var limits = AdmissionLimits.defaults()
            AdmissionNamespace.entries.forEach { limits = limits.withLimit(it, 1_000) }
            return limits
        }

        private fun keyBytes(seed: Int): ByteArray = ByteArray(32) { (seed + it).toByte() }

        private fun digestService(): VoucherDigestService =
            VoucherDigestService(
                DigestKey.of(7, keyBytes(7)),
                DigestKey.of(4, keyBytes(4)),
                mapOf(
                    DigestPurpose.VERIFICATION to DigestKeyRing.of(DigestKey.of(1, keyBytes(1))),
                    DigestPurpose.USER_IDENTITY to DigestKeyRing.of(DigestKey.of(2, keyBytes(2))),
                    DigestPurpose.REDIS_SIGNAL to DigestKeyRing.of(DigestKey.of(3, keyBytes(3))),
                    DigestPurpose.AUDIT to DigestKeyRing.of(DigestKey.of(5, keyBytes(5))),
                ),
            )
    }
}

private class RecordingJdbcMetrics : VoucherPoolJdbcMetrics {
    val acquisitionTimeouts = AtomicInteger()
    val permitTimeouts = AtomicInteger()
    val deadlineTimeouts = AtomicInteger()

    override fun timedOut(
        lane: io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcExecutionLane,
        phase: JdbcTimeoutPhase,
    ) {
        when (phase) {
            JdbcTimeoutPhase.ACQUISITION -> acquisitionTimeouts.incrementAndGet()
            JdbcTimeoutPhase.PERMIT -> permitTimeouts.incrementAndGet()
            JdbcTimeoutPhase.LOCK,
            JdbcTimeoutPhase.TRANSACTION,
            -> deadlineTimeouts.incrementAndGet()
        }
    }
}

private class OneShotAcquisitionTimeoutDataSource(
    private val delegate: HikariDataSource,
    private val connectionsToHold: Int,
) : DataSource {
    private val arm = AtomicBoolean(true)
    private val holderDone = CountDownLatch(1)
    private val holderFailure = AtomicReference<Throwable?>()

    override fun getConnection(): Connection = decorate(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        decorate(delegate.getConnection(username, password))

    @Suppress("SwallowedException") // Reflective invocation must expose the JDBC cause, not its wrapper.
    private fun decorate(connection: Connection): Connection {
        if (!arm.compareAndSet(true, false)) return connection
        val trigger = AtomicBoolean(true)
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            try {
                val result = method.invoke(connection, *arguments.orEmpty())
                if (method.name == "close" && trigger.compareAndSet(true, false)) holdEntirePool()
                result
            } catch (failure: InvocationTargetException) {
                throw checkNotNull(failure.cause)
            }
        } as Connection
    }

    private fun holdEntirePool() {
        val acquired = CountDownLatch(1)
        Thread.ofVirtual().name("voucher-timeout-holder").start {
            val held = ArrayList<Connection>(connectionsToHold)
            try {
                repeat(connectionsToHold) { held += delegate.connection }
                acquired.countDown()
                Thread.sleep(POOL_HOLD_MILLIS)
            } catch (failure: Throwable) {
                holderFailure.set(failure)
                acquired.countDown()
            } finally {
                held.forEach(Connection::close)
                holderDone.countDown()
            }
        }
        check(acquired.await(HOLDER_START_SECONDS, TimeUnit.SECONDS)) { "timeout holder did not acquire the pool" }
        holderFailure.get()?.let { throw IllegalStateException("timeout holder failed", it) }
    }

    fun awaitHolder() {
        check(holderDone.await(HOLDER_STOP_SECONDS, TimeUnit.SECONDS)) { "timeout holder did not release the pool" }
        holderFailure.get()?.let { throw IllegalStateException("timeout holder failed", it) }
    }

    override fun getLogWriter(): PrintWriter? = delegate.logWriter
    override fun setLogWriter(out: PrintWriter?) { delegate.logWriter = out }
    override fun setLoginTimeout(seconds: Int) { delegate.loginTimeout = seconds }
    override fun getLoginTimeout(): Int = delegate.loginTimeout
    override fun getParentLogger(): Logger = delegate.parentLogger
    override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)
    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)

    companion object {
        private const val POOL_HOLD_MILLIS = 800L
        private const val HOLDER_START_SECONDS = 2L
        private const val HOLDER_STOP_SECONDS = 2L
    }
}

internal class StressEvidenceWriter(private val outputRoot: Path) {
    fun write(evidence: VoucherPoolStressEvidence, threadDump: String) {
        val runDirectory = outputRoot.resolve(evidence.runId)
        Files.createDirectories(runDirectory)
        val profile = "${evidence.clients}-${evidence.redisMode.name.lowercase()}"
        val json = runDirectory.resolve("$profile.json")
        val dump = runDirectory.resolve("$profile.threads.txt")
        Files.writeString(json, evidence.toJson(), StandardCharsets.UTF_8)
        Files.writeString(dump, threadDump, StandardCharsets.UTF_8)
        val entry = ArtifactEntry(profile, json.fileName.toString(), sha256(json), dump.fileName.toString(), sha256(dump))
        val entries = manifests.computeIfAbsent(evidence.runId) { ConcurrentHashMap() }
        entries[profile] = entry
        writeManifest(runDirectory, evidence.runId, entries.values.sortedBy { it.profile })
    }

    private fun writeManifest(runDirectory: Path, runId: String, entries: List<ArtifactEntry>) {
        val body = buildString {
            append("{\n  \"runId\": \"").append(runId.json()).append("\",\n  \"profiles\": [\n")
            entries.forEachIndexed { index, entry ->
                append("    {\"profile\":\"").append(entry.profile)
                    .append("\",\"evidence\":\"").append(entry.evidence)
                    .append("\",\"evidenceSha256\":\"").append(entry.evidenceSha256)
                    .append("\",\"threadDump\":\"").append(entry.threadDump)
                    .append("\",\"threadDumpSha256\":\"").append(entry.threadDumpSha256).append("\"}")
                if (index < entries.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n}\n")
        }
        Files.writeString(runDirectory.resolve("manifest.json"), body, StandardCharsets.UTF_8)
    }

    private fun VoucherPoolStressEvidence.toJson(): String =
        javaClass.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .joinToString(prefix = "{\n", postfix = "\n}\n", separator = ",\n") { field ->
                field.trySetAccessible()
                val value = field.get(this)
                val encoded = if (value is Number) value.toString() else "\"${value.toString().json()}\""
                "  \"${field.name}\": $encoded"
            }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun String.json(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private data class ArtifactEntry(
        val profile: String,
        val evidence: String,
        val evidenceSha256: String,
        val threadDump: String,
        val threadDumpSha256: String,
    )

    companion object {
        private val manifests = ConcurrentHashMap<String, ConcurrentHashMap<String, ArtifactEntry>>()
    }
}

private fun stressThreadDump(profile: String, loadThreads: Set<Thread>): String = buildString {
    append("profile=").append(profile).append('\n')
    append("stressTasks=").append(loadThreads.size).append('\n')
    loadThreads.sortedBy(Thread::threadId).forEach { thread ->
        append("stress-task id=").append(thread.threadId())
            .append(" virtual=").append(thread.isVirtual)
            .append(" state=").append(thread.state).append('\n')
        thread.stackTrace.take(12).forEach { frame -> append("  at ").append(frame).append('\n') }
    }
    Thread.getAllStackTraces().entries.sortedBy { it.key.name }.take(256).forEach { (thread, frames) ->
        append('"').append(thread.name).append("\" state=").append(thread.state).append('\n')
        frames.take(12).forEach { frame -> append("  at ").append(frame).append('\n') }
    }
}
