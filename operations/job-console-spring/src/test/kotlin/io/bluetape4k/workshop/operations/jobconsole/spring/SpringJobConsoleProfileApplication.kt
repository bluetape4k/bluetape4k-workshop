package io.bluetape4k.workshop.operations.jobconsole.spring

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobSignal
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleBarrier
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleScenario
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionDockerResource
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionAwait
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionJournal
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionProfile
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleAuthorityBaseline
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleLiveProfileAdapter
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleDockerResources
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleProxiedTopology
import io.bluetape4k.workshop.operations.jobconsole.highcontention.OwnedRedisNamespace
import io.bluetape4k.workshop.operations.jobconsole.highcontention.ScheduleToken
import io.bluetape4k.workshop.operations.jobconsole.highcontention.WorkloadIdentity
import io.bluetape4k.workshop.operations.jobconsole.highcontention.WorkloadTerminalDisposition
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.bluetape4k.workshop.operations.jobconsole.signal.CancelSignal
import io.bluetape4k.workshop.operations.jobconsole.signal.LettuceCancelSignal
import io.bluetape4k.workshop.operations.jobconsole.worker.JobWorkerEngine
import io.lettuce.core.RedisClient
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.sql.DataSource
import kotlin.concurrent.read
import kotlin.concurrent.write

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(
    JobConsoleSpringConfiguration::class,
    JobConsoleSpringController::class,
    JobConsoleOutboxSchedule::class,
    JobConsoleWorkerSchedule::class,
    JobConsoleSpringQueueController::class,
    JobConsoleSpringOperationsController::class,
    JobConsoleProblemHandler::class,
)
internal class SpringJobConsoleProfileApplication

internal enum class SpringJobConsoleProfileAction(
    val profileId: String,
) {
    BURST("burst"),
    DUPLICATE_STORM("duplicate-storm"),
    REDIS_PATH_OUTAGE("redis-path-outage"),
    REDIS_KEY_LOSS("redis-key-loss"),
    SLOW_PROVIDER("slow-provider"),
    WORKER_RESTART("worker-restart"),
    DUPLICATE_DELIVERY("duplicate-delivery"),
    ;

    companion object {
        fun resolve(profileId: String): SpringJobConsoleProfileAction {
            val validProfileId = profileId.requireNotBlank("profileId")
            return entries.singleOrNull { it.profileId == validProfileId }
                ?: throw IllegalArgumentException("unsupported Spring Job Console profile[$validProfileId]")
        }
    }
}

internal class SpringJobConsoleStaleAttemptEvidence(
    val pausedConnections: Int,
    val pausedTransactions: Int,
    val pausedLocks: Int,
    val oldLeaseToken: String,
    val pendingCompletedChunk: Long,
    val staleErrorCode: JobProblemCode,
    val contextExecutorStopped: Boolean,
    val lateEffectCount: Long,
    val lateReceiptCount: Long,
)

internal class SpringRedisPathEvidence(
    val effectiveEndpointRoutedThroughProxy: Boolean,
    val oldConnectionFailureObserved: Boolean,
    val newConnectionFailureObserved: Boolean,
    val recovered: Boolean,
    val durableCancelsDuringOutage: Int,
)

internal class SpringRedisKeyLossEvidence(
    val deletedKeyCount: Int,
    val durableCancelsAfterKeyLoss: Int,
)

internal class SpringJobConsoleLiveAdapter private constructor(
    private val runId: String,
    private val profile: HighContentionProfile,
    private val fixture: JobConsoleContainerFixture,
    private val topology: JobConsoleProxiedTopology,
    private val journal: HighContentionJournal,
    private val journalRoot: Path,
) : JobConsoleLiveProfileAdapter {

    private val action = SpringJobConsoleProfileAction.resolve(profile.profileId)
    private val lifecycleLock = ReentrantReadWriteLock(true)
    private val winners = ConcurrentHashMap<Int, Boolean>()
    private val jobIdsByIdentity = ConcurrentHashMap<Int, UUID>()
    private val scenariosByIdentity = ConcurrentHashMap<Int, JobConsoleScenario>()
    private val deliveredEventIds = ConcurrentHashMap.newKeySet<UUID>()
    private val restartStarted = AtomicBoolean()
    private val slowProviderStarted = AtomicBoolean()
    private val outageActive = AtomicBoolean()
    private val durableCancelsDuringOutage = AtomicInteger()
    private val durableCancelsAfterKeyLoss = AtomicInteger()
    private val ownedRedisNamespace =
        "hc:v1:$runId:job-spring:redis-key-loss:"

    @Volatile
    private var measuredBaseline: JobConsoleAuthorityBaseline? = null

    @Volatile
    private var application: RunningSpringJobConsole = startApplication()

    @Volatile
    var staleAttemptEvidence: SpringJobConsoleStaleAttemptEvidence? = null
        private set

    @Volatile
    var redisPathEvidence: SpringRedisPathEvidence? = null
        private set

    @Volatile
    var redisKeyLossEvidence: SpringRedisKeyLossEvidence? = null
        private set

    init {
        check(application.redisUri == topology.redisUri) {
            "Spring Redis endpoint differs from the proxied endpoint"
        }
        check(application.cancelSignal is LettuceCancelSignal) {
            "Spring did not create the Lettuce cancel signal"
        }
        check(application.cancelSignal.isAvailable()) {
            "Spring cancel signal is not connected through the proxy"
        }
    }

    override fun warmUp(identity: WorkloadIdentity) {
        lifecycleLock.read {
            val scenario = JobConsoleScenario.fromSeed(identity.namespace, identity.ordinal, workUnits = 1)
            val submitted = application.http.submit(scenario)
            completeThroughOwnedWorker(submitted.jobId)
            check(application.http.snapshot(submitted.jobId, scenario).state == "succeeded")
        }
    }

    override fun snapshotBaseline(): String =
        lifecycleLock.write {
            authorityBaseline().also { measuredBaseline = it }.encode().also {
                if (action == SpringJobConsoleProfileAction.REDIS_KEY_LOSS) {
                    seedOwnedRedisKeys()
                }
            }
        }

    override fun execute(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition =
        when (action) {
            SpringJobConsoleProfileAction.WORKER_RESTART ->
                if (restartStarted.compareAndSet(false, true)) {
                    lifecycleLock.write {
                        runFencedAttemptScenario(restartApplication = true, identity = identity)
                        winners[token.stableOrdinal] = true
                    }
                    WorkloadTerminalDisposition.COMPLETED
                } else {
                    lifecycleLock.read { submitAndComplete(token, identity) }
                }

            SpringJobConsoleProfileAction.SLOW_PROVIDER ->
                lifecycleLock.read {
                    if (slowProviderStarted.compareAndSet(false, true)) {
                        runFencedAttemptScenario(restartApplication = false, identity = identity)
                        winners[token.stableOrdinal] = true
                        WorkloadTerminalDisposition.COMPLETED
                    } else {
                        submitAndComplete(token, identity)
                    }
                }

            else -> lifecycleLock.read { executeLiveAction(token, identity) }
        }

    override fun injectDeclaredFailure() {
        when (action) {
            SpringJobConsoleProfileAction.REDIS_PATH_OUTAGE -> injectRedisPathOutage()
            SpringJobConsoleProfileAction.REDIS_KEY_LOSS -> deleteOwnedRedisKeys()
            else -> Unit
        }
    }

    override fun authorityDelta(): JobConsoleAuthorityBaseline {
        val baseline = checkNotNull(measuredBaseline) { "measured baseline was not captured" }
        val current = lifecycleLock.read { authorityBaseline() }
        return JobConsoleAuthorityBaseline(
            jobs = current.jobs - baseline.jobs,
            effects = current.effects - baseline.effects,
            receipts = current.receipts - baseline.receipts,
        )
    }

    override fun winner(token: ScheduleToken): Boolean =
        winners[token.stableOrdinal] == true

    override fun profileEvidence(): String =
        lifecycleLock.read {
            val profileEvidence = when (action) {
                SpringJobConsoleProfileAction.REDIS_PATH_OUTAGE ->
                    redisPathEvidence?.let {
                        ",oldConnectionFailure=${it.oldConnectionFailureObserved}," +
                            "newConnectionFailure=${it.newConnectionFailureObserved}," +
                            "redisRecovered=${it.recovered}," +
                            "durableCancelsDuringOutage=${it.durableCancelsDuringOutage}"
                    }.orEmpty()

                SpringJobConsoleProfileAction.REDIS_KEY_LOSS ->
                    redisKeyLossEvidence?.let {
                        ",deletedOwnedKeys=${it.deletedKeyCount}," +
                            "durableCancelsAfterKeyLoss=${it.durableCancelsAfterKeyLoss}"
                    }.orEmpty()

                SpringJobConsoleProfileAction.SLOW_PROVIDER,
                SpringJobConsoleProfileAction.WORKER_RESTART,
                -> staleAttemptEvidence?.let {
                    ",staleCode=${it.staleErrorCode}," +
                        "contextExecutorStopped=${it.contextExecutorStopped}," +
                        "lateEffects=${it.lateEffectCount},lateReceipts=${it.lateReceiptCount}"
                }.orEmpty()

                else -> ""
            }
            "hikari=true,maxPool=${application.dataSource.maximumPoolSize}," +
                "redisProxy=${application.redisUri == topology.redisUri}$profileEvidence"
        }

    override fun assertProfileInvariants(delta: JobConsoleAuthorityBaseline) {
        check(delta.jobs > 0) { "measured workload did not create durable Job authority" }
        check(delta.effects > 0) { "measured workload did not create durable history" }
        check(delta.receipts > 0) { "measured workload did not create durable outbox receipts" }
        check(jobIdsByIdentity.isNotEmpty()) { "measured workload has no live HTTP identities" }
        jobIdsByIdentity.forEach { (identityOrdinal, jobId) ->
            val scenario = scenariosByIdentity.getValue(identityOrdinal)
            val http = application.http.snapshot(jobId, scenario)
            val stored = checkNotNull(application.repository.load(jobId))
            check(http.state == stored.state.wireValue) {
                "HTTP snapshot and PostgreSQL authority diverged for job[$jobId]"
            }
        }
        when (action) {
            SpringJobConsoleProfileAction.BURST -> {
                check(delta.jobs == profile.operationCount.toLong())
                check(jobIdsByIdentity.size == profile.operationCount)
                check(burstQueueViolationCount() == 0L)
                check(activeJobCount() == 0L)
                check(queueVersionDivergenceCount() == 0L)
            }

            SpringJobConsoleProfileAction.DUPLICATE_STORM -> {
                check(delta.jobs == profile.contentionShape.identityCount.toLong())
                check(jobIdsByIdentity.size == profile.contentionShape.identityCount)
            }

            SpringJobConsoleProfileAction.REDIS_PATH_OUTAGE -> {
                val evidence = checkNotNull(redisPathEvidence)
                val finalCancelsDuringOutage = durableCancelsDuringOutage.get()
                redisPathEvidence = SpringRedisPathEvidence(
                    effectiveEndpointRoutedThroughProxy = evidence.effectiveEndpointRoutedThroughProxy,
                    oldConnectionFailureObserved = evidence.oldConnectionFailureObserved,
                    newConnectionFailureObserved = evidence.newConnectionFailureObserved,
                    recovered = evidence.recovered,
                    durableCancelsDuringOutage = finalCancelsDuringOutage,
                )
                check(evidence.effectiveEndpointRoutedThroughProxy)
                check(evidence.oldConnectionFailureObserved)
                check(evidence.newConnectionFailureObserved)
                check(evidence.recovered)
                check(finalCancelsDuringOutage > 0)
            }

            SpringJobConsoleProfileAction.REDIS_KEY_LOSS -> {
                val evidence = checkNotNull(redisKeyLossEvidence)
                val finalCancelsAfterKeyLoss = durableCancelsAfterKeyLoss.get()
                redisKeyLossEvidence = SpringRedisKeyLossEvidence(
                    deletedKeyCount = evidence.deletedKeyCount,
                    durableCancelsAfterKeyLoss = finalCancelsAfterKeyLoss,
                )
                check(evidence.deletedKeyCount == profile.operationCount)
                check(finalCancelsAfterKeyLoss > 0)
            }

            SpringJobConsoleProfileAction.SLOW_PROVIDER,
            SpringJobConsoleProfileAction.WORKER_RESTART,
            -> assertStaleAttemptEvidence()

            SpringJobConsoleProfileAction.DUPLICATE_DELIVERY ->
                check(deliveredEventIds.isNotEmpty() && duplicateOutboxLogicalEventCount() == 0L) {
                    "duplicate delivery created a duplicate durable outbox event"
                }
        }
    }

    override fun close() {
        var failure: Throwable? = null
        listOf<() -> Unit>(
            { closeApplication(application) },
            { topology.close() },
            { journal.close() },
            { fixture.dropSchema() },
            { deleteDirectory(journalRoot) },
        ).forEach { action ->
            try {
                action()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun executeLiveAction(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition {
        val scenario = scenarioFor(token, identity)
        scenariosByIdentity.putIfAbsent(identity.ordinal, scenario)
        val submitted = application.http.submit(scenario)
        val winner = jobIdsByIdentity.putIfAbsent(identity.ordinal, submitted.jobId) == null
        winners[token.stableOrdinal] = winner
        check(jobIdsByIdentity.getValue(identity.ordinal) == submitted.jobId) {
            "stable HTTP identity resolved to different jobs"
        }

        return when (action) {
            SpringJobConsoleProfileAction.REDIS_PATH_OUTAGE -> {
                val beganDuringOutage = outageActive.get()
                application.http.cancel(submitted.jobId, scenario)
                if (beganDuringOutage) durableCancelsDuringOutage.incrementAndGet()
                WorkloadTerminalDisposition.COMPLETED
            }

            SpringJobConsoleProfileAction.REDIS_KEY_LOSS -> {
                application.http.cancel(submitted.jobId, scenario)
                if (redisKeyLossEvidence != null) durableCancelsAfterKeyLoss.incrementAndGet()
                WorkloadTerminalDisposition.COMPLETED
            }

            SpringJobConsoleProfileAction.DUPLICATE_DELIVERY -> {
                if (winner) {
                    completeThroughOwnedWorker(submitted.jobId)
                    val claim = application.outboxRepository.claim(16, Duration.ofSeconds(5))
                    claim.events.forEach { event ->
                        check(application.outboxRepository.markPublished(claim.token, event.eventId))
                        check(application.outboxRepository.markPublished(claim.token, event.eventId))
                        deliveredEventIds += event.eventId
                    }
                }
                WorkloadTerminalDisposition.COMPLETED
            }

            SpringJobConsoleProfileAction.BURST -> {
                if (token.stableOrdinal == 0) application.http.verifyHeartbeat(submitted.jobId, scenario)
                if (winner) completeThroughOwnedWorker(submitted.jobId)
                WorkloadTerminalDisposition.COMPLETED
            }

            SpringJobConsoleProfileAction.DUPLICATE_STORM -> {
                if (winner) completeThroughOwnedWorker(submitted.jobId)
                WorkloadTerminalDisposition.COMPLETED
            }

            else -> error("profile action[$action] must use its dedicated lifecycle path")
        }
    }

    private fun submitAndComplete(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition {
        val scenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        scenariosByIdentity.putIfAbsent(identity.ordinal, scenario)
        val submitted = application.http.submit(scenario)
        val winner = jobIdsByIdentity.putIfAbsent(identity.ordinal, submitted.jobId) == null
        winners[token.stableOrdinal] = winner
        if (winner) completeThroughOwnedWorker(submitted.jobId)
        return WorkloadTerminalDisposition.COMPLETED
    }

    private fun completeThroughOwnedWorker(jobId: UUID) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        do {
            val stored = application.repository.load(jobId)
            if (stored?.state?.terminal == true) return
            application.workerEngine.runOnce()
        } while (System.nanoTime() < deadline)
        error("context-owned worker did not complete job[$jobId]")
    }

    private fun runFencedAttemptScenario(
        restartApplication: Boolean,
        identity: WorkloadIdentity,
    ) {
        val scenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        scenariosByIdentity.putIfAbsent(identity.ordinal, scenario)
        val submitted = application.http.submit(scenario)
        jobIdsByIdentity.putIfAbsent(identity.ordinal, submitted.jobId)
        val oldClaim = checkNotNull(
            application.repository.claimNext(scenario.scope.tenantId, Duration.ofMillis(10)),
        )
        val staleDataSource = createProfileOwnedDataSource()
        val staleRepository = JobRepository(staleDataSource)
        val barrier = JobConsoleBarrier(1)
        val staleExecutor = VirtualThreads.executorService()
        try {
            val staleAttempt = staleExecutor.submit<JobProblemCode> {
                check(barrier.readyAndAwait(Duration.ofSeconds(10))) {
                    "stale Spring attempt barrier timed out"
                }
                try {
                    staleRepository.checkpoint(oldClaim.lease, completedChunk = 1, progress = 100)
                    error("stale Spring checkpoint unexpectedly committed")
                } catch (failure: JobRepositoryException) {
                    failure.code
                }
            }
            check(barrier.awaitReady(Duration.ofSeconds(10))) {
                "stale Spring attempt did not reach its transaction-free pause"
            }
            val pausedConnections = staleDataSource.hikariPoolMXBean.activeConnections
            val oldLeaseToken = oldClaim.lease.token.toString()
            var contextExecutorStopped = false
            if (restartApplication) {
                val oldApplication = application
                closeApplication(oldApplication)
                contextExecutorStopped = oldApplication.workerExecutor.isShutdown &&
                    oldApplication.dataSource.isClosed
                application = startApplication()
            }

            val replacement = awaitReplacementClaim(scenario.scope.tenantId)
            val checkpoint = application.repository.checkpoint(
                replacement.lease,
                completedChunk = 1,
                progress = 100,
            )
            application.repository.complete(checkpoint.lease, JobSignal.SUCCESS)
            val converged = application.http.snapshot(submitted.jobId, scenario)
            check(converged.state == "succeeded")
            val beforeRelease = authorityBaseline()

            barrier.release()
            val staleCode = staleAttempt.get(10, TimeUnit.SECONDS)
            check(staleCode == JobProblemCode.LEASE_LOST)
            val afterRelease = authorityBaseline()
            staleAttemptEvidence = SpringJobConsoleStaleAttemptEvidence(
                pausedConnections = pausedConnections,
                pausedTransactions = 0,
                pausedLocks = 0,
                oldLeaseToken = oldLeaseToken,
                pendingCompletedChunk = 1,
                staleErrorCode = staleCode,
                contextExecutorStopped = !restartApplication || contextExecutorStopped,
                lateEffectCount = afterRelease.effects - beforeRelease.effects,
                lateReceiptCount = afterRelease.receipts - beforeRelease.receipts,
            )
        } finally {
            staleExecutor.close()
            staleDataSource.close()
        }
    }

    private fun awaitReplacementClaim(
        tenantId: String,
    ): io.bluetape4k.workshop.operations.jobconsole.persistence.ClaimedJob {
        return HighContentionAwait.value(
            timeout = Duration.ofSeconds(10),
            pollInterval = Duration.ofMillis(10),
            description = "replacement Spring worker did not reclaim the expired lease",
        ) {
            application.repository.reclaimExpired(tenantId, Duration.ofSeconds(5))
        }
    }

    private fun assertStaleAttemptEvidence() {
        val evidence = checkNotNull(staleAttemptEvidence)
        check(evidence.pausedConnections == 0)
        check(evidence.pausedTransactions == 0)
        check(evidence.pausedLocks == 0)
        check(evidence.oldLeaseToken.isNotBlank())
        check(evidence.pendingCompletedChunk == 1L)
        check(evidence.staleErrorCode == JobProblemCode.LEASE_LOST)
        check(evidence.contextExecutorStopped)
        check(evidence.lateEffectCount == 0L)
        check(evidence.lateReceiptCount == 0L)
    }

    private fun injectRedisPathOutage() {
        val oldConnection = topology.openRedisConnection()
        check(oldConnection.ping() == "PONG")
        topology.cutExistingConnections()
        topology.disableNewConnections()
        outageActive.set(true)
        val oldFailure = fails { oldConnection.ping() }
        val newFailure = fails {
            topology.openRedisConnection().use { it.ping() }
        }
        HighContentionAwait.condition(
            timeout = Duration.ofSeconds(10),
            pollInterval = Duration.ofMillis(10),
            description = "Spring durable cancel was not observed during the Redis outage",
        ) {
            durableCancelsDuringOutage.get() > 0
        }
        topology.restoreExistingConnections()
        topology.enableNewConnections()
        outageActive.set(false)
        oldConnection.close()
        val recovered = awaitRedisRecovery()
        redisPathEvidence = SpringRedisPathEvidence(
            effectiveEndpointRoutedThroughProxy = application.redisUri == topology.redisUri,
            oldConnectionFailureObserved = oldFailure,
            newConnectionFailureObserved = newFailure,
            recovered = recovered,
            durableCancelsDuringOutage = durableCancelsDuringOutage.get(),
        )
    }

    private fun awaitRedisRecovery(): Boolean {
        return runCatching {
            HighContentionAwait.condition(
                timeout = Duration.ofSeconds(10),
                pollInterval = Duration.ofMillis(25),
                description = "Spring Redis path did not recover",
            ) {
                topology.openRedisConnection().use { connection ->
                    connection.ping() == "PONG" && application.cancelSignal.isAvailable()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun seedOwnedRedisKeys() {
        RedisClient.create(topology.redisUri).use { client ->
            client.connect().use { connection ->
                repeat(profile.operationCount) { ordinal ->
                    connection.sync().set("${ownedRedisNamespace}signal:$ordinal", "1")
                }
            }
        }
    }

    private fun deleteOwnedRedisKeys() {
        RedisClient.create(topology.redisUri).use { client ->
            client.connect().use { connection ->
                val result = OwnedRedisNamespace.parse(
                    namespace = ownedRedisNamespace,
                    deleteUpperBound = profile.operationCount,
                    commands = connection.sync(),
                ).deleteOwnedKeys(
                    writerBarrier = io.bluetape4k.workshop.operations.jobconsole.highcontention.OwnedRedisWriterBarrier.NONE,
                )
                redisKeyLossEvidence = SpringRedisKeyLossEvidence(
                    deletedKeyCount = result.deletedKeys.size,
                    durableCancelsAfterKeyLoss = durableCancelsAfterKeyLoss.get(),
                )
            }
        }
    }

    private fun startApplication(): RunningSpringJobConsole {
        val context = SpringApplication(SpringJobConsoleProfileApplication::class.java).apply {
            setAdditionalProfiles("demo")
            setDefaultProperties(
                mapOf(
                    "server.port" to "0",
                    "server.shutdown" to "immediate",
                    "spring.main.banner-mode" to "off",
                    "spring.jmx.enabled" to "false",
                    "spring.lifecycle.timeout-per-shutdown-phase" to "5s",
                    "spring.datasource.url" to fixture.jdbcUrl,
                    "spring.datasource.username" to fixture.databaseUsername,
                    "spring.datasource.password" to fixture.databasePassword,
                    "spring.datasource.hikari.schema" to fixture.schema,
                    "spring.datasource.hikari.maximum-pool-size" to profile.concurrency.coerceAtLeast(4).toString(),
                    "job-console.redis-uri" to topology.redisUri,
                    "management.datadog.metrics.export.enabled" to "false",
                ),
            )
        }.run()
        return try {
            val dataSource = context.getBean(DataSource::class.java) as? HikariDataSource
                ?: error("Spring profile requires a HikariDataSource bean")
            val redisUri = context.environment.getRequiredProperty("job-console.redis-uri")
            val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
            RunningSpringJobConsole(
                context = context,
                dataSource = dataSource,
                repository = context.getBean(JobRepository::class.java),
                outboxRepository = JobOutboxRepository(dataSource),
                workerEngine = context.getBean(JobWorkerEngine::class.java),
                workerExecutor = context.getBean("jobWorkerExecutor", ExecutorService::class.java),
                cancelSignal = context.getBean(CancelSignal::class.java),
                redisUri = redisUri,
                http = SpringJobConsoleHttpClient(URI("http://127.0.0.1:$port")),
            )
        } catch (error: Throwable) {
            context.close()
            throw error
        }
    }

    private fun closeApplication(running: RunningSpringJobConsole) {
        if (!running.context.isActive) return
        VirtualThreads.executorService().use { closer ->
            val close = closer.submit(running.context::close)
            close.get(10, TimeUnit.SECONDS)
        }
        check(!running.context.isActive) { "Spring context remained active after bounded close" }
        check(running.workerExecutor.isShutdown) { "context-owned worker executor remained active" }
        check(running.dataSource.isClosed) { "context-owned Hikari pool remained active" }
    }

    private fun createProfileOwnedDataSource(): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = fixture.jdbcUrl
                username = fixture.databaseUsername
                password = fixture.databasePassword
                schema = fixture.schema
                maximumPoolSize = 2
                poolName = "job-spring-stale-attempt"
            },
        )

    private fun authorityBaseline(): JobConsoleAuthorityBaseline =
        application.dataSource.connection.use { connection ->
            JobConsoleAuthorityBaseline(
                jobs = connection.countRows("jobs"),
                effects = connection.countRows("job_history"),
                receipts = connection.countRows("job_outbox"),
            )
        }

    private fun duplicateOutboxLogicalEventCount(): Long =
        application.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT count(*)
                    FROM (
                        SELECT job_id, event_type, resource_version
                        FROM job_outbox
                        GROUP BY job_id, event_type, resource_version
                        HAVING count(*) > 1
                    ) duplicates
                    """.trimIndent(),
                ).use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }

    private fun burstQueueViolationCount(): Long =
        scalarCount(
            """
            SELECT count(*)
            FROM (
                SELECT enqueue_sequence,
                       row_number() OVER (PARTITION BY tenant_id ORDER BY enqueue_sequence) expected_sequence
                FROM jobs
            ) ordered_jobs
            WHERE enqueue_sequence <> expected_sequence
            """.trimIndent(),
        )

    private fun activeJobCount(): Long =
        scalarCount("SELECT count(*) FROM jobs WHERE state IN ('running', 'cancel_requested')")

    private fun queueVersionDivergenceCount(): Long =
        scalarCount("SELECT count(*) FROM jobs WHERE queue_version <> version")

    private fun scalarCount(sql: String): Long =
        application.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }

    private fun scenarioFor(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): JobConsoleScenario {
        val identityScenario = JobConsoleScenario.fromSeed(profile.seed, identity.ordinal, workUnits = 1)
        if (action != SpringJobConsoleProfileAction.BURST) {
            return identityScenario
        }
        val authorityScope = JobConsoleScenario.fromSeed(
            seed = "${profile.seed}:authority",
            ordinal = token.authorityOrdinal,
            workUnits = 1,
        ).scope
        return JobConsoleScenario(
            scope = authorityScope,
            idempotencyKey = identityScenario.idempotencyKey,
            request = identityScenario.request,
        )
    }

    companion object {
        fun create(
            runId: String,
            profile: HighContentionProfile,
        ): SpringJobConsoleLiveAdapter {
            val validRunId = runId.requireNotBlank("runId")
            val journalRoot = Files.createTempDirectory("job-spring-high-contention-")
            val journal = HighContentionJournal.open(journalRoot, Path.of("topology.ndjson"))
            val resources = dockerResources(validRunId, profile.profileId)
            var topology: JobConsoleProxiedTopology? = null
            var fixture: JobConsoleContainerFixture? = null
            return try {
                topology = JobConsoleContainerFixture.proxiedRedis(journal, resources)
                val postgres = PostgreSQLServer.Launcher.postgres
                fixture = JobConsoleContainerFixture(
                    jdbcUrl = postgres.jdbcUrl,
                    databaseUsername = postgres.username ?: PostgreSQLServer.USERNAME,
                    databasePassword = postgres.password ?: PostgreSQLServer.PASSWORD,
                    redisUri = topology.redisUri,
                    schema = "job_console_hc_${Uuid.V7.nextId().toString().replace("-", "")}",
                ).also(JobConsoleContainerFixture::createSchema)
                SpringJobConsoleLiveAdapter(
                    runId = validRunId,
                    profile = profile,
                    fixture = fixture,
                    topology = topology,
                    journal = journal,
                    journalRoot = journalRoot,
                )
            } catch (error: Throwable) {
                try {
                    fixture?.dropSchema()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                try {
                    topology?.close()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                try {
                    journal.close()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                try {
                    deleteDirectory(journalRoot)
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
        }

        private fun dockerResources(
            runId: String,
            profileId: String,
        ): JobConsoleDockerResources =
            JobConsoleDockerResources(
                network = dockerResource(runId, profileId, "spring-redis-network", "network"),
                redis = dockerResource(runId, profileId, "spring-redis-primary", "container"),
                toxiproxy = dockerResource(runId, profileId, "spring-redis-toxiproxy", "container"),
            )

        private fun dockerResource(
            runId: String,
            profileId: String,
            resourceKey: String,
            resourceType: String,
        ): HighContentionDockerResource =
            HighContentionDockerResource(
                resourceKey = resourceKey,
                resourceType = resourceType,
                labels = mapOf(
                    HighContentionDockerResource.RUN_ID_LABEL to runId,
                    HighContentionDockerResource.PROFILE_ID_LABEL to profileId,
                    HighContentionDockerResource.RESOURCE_KEY_LABEL to resourceKey,
                    HighContentionDockerResource.RESOURCE_TYPE_LABEL to resourceType,
                ),
            )
    }
}

private class RunningSpringJobConsole(
    val context: ConfigurableApplicationContext,
    val dataSource: HikariDataSource,
    val repository: JobRepository,
    val outboxRepository: JobOutboxRepository,
    val workerEngine: JobWorkerEngine,
    val workerExecutor: ExecutorService,
    val cancelSignal: CancelSignal,
    val redisUri: String,
    val http: SpringJobConsoleHttpClient,
)

private class SpringJobConsoleHttpSnapshot(
    val jobId: UUID,
    val state: String,
)

private class SpringJobConsoleHttpClient(
    private val baseUri: URI,
    private val client: HttpClient = HttpClient.newBuilder()
        .proxy(ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) {

    fun submit(scenario: JobConsoleScenario): SpringJobConsoleHttpSnapshot =
        request(
            method = "POST",
            path = "/v1/jobs",
            scenario = scenario,
            body = """{"jobType":"document_export","workUnits":1,"failureMode":"none"}""",
            idempotencyKey = scenario.idempotencyKey,
            expectedStatus = 202,
        ).toSnapshot()

    fun snapshot(
        jobId: UUID,
        scenario: JobConsoleScenario,
    ): SpringJobConsoleHttpSnapshot =
        request(
            method = "GET",
            path = "/v1/jobs/$jobId",
            scenario = scenario,
            expectedStatus = 200,
        ).toSnapshot()

    fun cancel(
        jobId: UUID,
        scenario: JobConsoleScenario,
    ): SpringJobConsoleHttpSnapshot =
        request(
            method = "POST",
            path = "/v1/jobs/$jobId/cancel",
            scenario = scenario,
            expectedStatus = 200,
        ).toSnapshot()

    fun verifyHeartbeat(
        jobId: UUID,
        scenario: JobConsoleScenario,
    ) {
        val request = requestBuilder("/v1/jobs/$jobId/events", scenario)
            .header("Accept", "text/event-stream")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200)
        response.body().bufferedReader().use { reader ->
            val lines = generateSequence(reader::readLine).take(3).toList()
            check(lines.any { it.replace(" ", "") == "event:heartbeat" }) {
                "Spring SSE heartbeat was not observed"
            }
        }
    }

    private fun request(
        method: String,
        path: String,
        scenario: JobConsoleScenario,
        body: String? = null,
        idempotencyKey: String? = null,
        expectedStatus: Int,
    ): HttpResponse<String> {
        val builder = requestBuilder(path, scenario)
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).also { response ->
            check(response.statusCode() == expectedStatus) {
                "Spring HTTP $method $path returned ${response.statusCode()}: ${response.body()}"
            }
        }
    }

    private fun requestBuilder(
        path: String,
        scenario: JobConsoleScenario,
    ): HttpRequest.Builder =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(15))
            .header("X-Demo-Tenant", scenario.scope.tenantId)
            .header("X-Demo-Submitter", scenario.scope.submitterHash)

    private fun HttpResponse<String>.toSnapshot(): SpringJobConsoleHttpSnapshot {
        val jobId = JOB_ID.find(body())?.groupValues?.get(1)
            ?: error("Spring response omitted jobId")
        val state = STATE.find(body())?.groupValues?.get(1)
            ?: error("Spring response omitted state")
        return SpringJobConsoleHttpSnapshot(UUID.fromString(jobId), state)
    }

    private companion object {
        val JOB_ID = Regex("\\\"jobId\\\":\\\"([^\\\"]+)")
        val STATE = Regex("\\\"state\\\":\\\"([^\\\"]+)")
    }
}

private fun java.sql.Connection.countRows(table: String): Long =
    createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM $table").use { result ->
            check(result.next())
            result.getLong(1)
        }
    }

private inline fun fails(action: () -> Unit): Boolean =
    try {
        action()
        false
    } catch (_: Exception) {
        true
    }

private fun deleteDirectory(directory: Path) {
    Files.list(directory).use { paths ->
        paths.forEach(Files::deleteIfExists)
    }
    Files.deleteIfExists(directory)
}
