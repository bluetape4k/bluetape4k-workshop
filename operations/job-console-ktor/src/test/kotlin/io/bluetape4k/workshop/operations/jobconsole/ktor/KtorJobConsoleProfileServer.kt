package io.bluetape4k.workshop.operations.jobconsole.ktor

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
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleDockerResources
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleLiveProfileAdapter
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleProxiedTopology
import io.bluetape4k.workshop.operations.jobconsole.highcontention.OwnedRedisNamespace
import io.bluetape4k.workshop.operations.jobconsole.highcontention.OwnedRedisWriterBarrier
import io.bluetape4k.workshop.operations.jobconsole.highcontention.ScheduleToken
import io.bluetape4k.workshop.operations.jobconsole.highcontention.WorkloadIdentity
import io.bluetape4k.workshop.operations.jobconsole.highcontention.WorkloadTerminalDisposition
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.lettuce.core.RedisClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal enum class KtorJobConsoleProfileAction(
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
        fun resolve(profileId: String): KtorJobConsoleProfileAction {
            val validProfileId = profileId.requireNotBlank("profileId")
            return entries.singleOrNull { it.profileId == validProfileId }
                ?: throw IllegalArgumentException("unsupported Ktor Job Console profile[$validProfileId]")
        }
    }
}

private class KtorStaleAttemptEvidence(
    val pausedConnections: Int,
    val pausedTransactions: Int,
    val pausedLocks: Int,
    val oldLeaseToken: String,
    val pendingCompletedChunk: Long,
    val staleErrorCode: JobProblemCode,
    val serverJobsStopped: Boolean,
    val oldPortReleased: Boolean,
    val lateEffectCount: Long,
    val lateReceiptCount: Long,
)

private class KtorRedisPathEvidence(
    val effectiveEndpointRoutedThroughProxy: Boolean,
    val oldConnectionFailureObserved: Boolean,
    val newConnectionFailureObserved: Boolean,
    val recovered: Boolean,
    val durableCancelsDuringOutage: Int,
)

private class KtorRedisKeyLossEvidence(
    val deletedKeyCount: Int,
    val durableCancelsAfterKeyLoss: Int,
)

internal class KtorJobConsoleLiveAdapter private constructor(
    private val runId: String,
    private val profile: HighContentionProfile,
    private val fixture: JobConsoleContainerFixture,
    private val topology: JobConsoleProxiedTopology,
    private val journal: HighContentionJournal,
    private val journalRoot: Path,
) : JobConsoleLiveProfileAdapter {

    private val action = KtorJobConsoleProfileAction.resolve(profile.profileId)
    private val lifecycleLock = ReentrantReadWriteLock(true)
    private val winners = ConcurrentHashMap<Int, Boolean>()
    private val jobIdsByIdentity = ConcurrentHashMap<Int, UUID>()
    private val scenariosByIdentity = ConcurrentHashMap<Int, JobConsoleScenario>()
    private val deliveredEventIds = ConcurrentHashMap.newKeySet<UUID>()
    private val restartStarted = AtomicBoolean()
    private val slowProviderStarted = AtomicBoolean()
    private val slowProviderReady = CompletableFuture<Unit>()
    private val outageActive = AtomicBoolean()
    private val durableCancelsDuringOutage = AtomicInteger()
    private val durableCancelsAfterKeyLoss = AtomicInteger()
    private val ownedRedisNamespace = "hc:v1:$runId:job-ktor:redis-key-loss:"

    @Volatile
    private var measuredBaseline: JobConsoleAuthorityBaseline? = null

    @Volatile
    private var application: RunningKtorJobConsole = startApplication()

    @Volatile
    private var staleAttemptEvidence: KtorStaleAttemptEvidence? = null

    @Volatile
    private var redisPathEvidence: KtorRedisPathEvidence? = null

    @Volatile
    private var redisKeyLossEvidence: KtorRedisKeyLossEvidence? = null

    init {
        check(application.redisUri == topology.redisUri) {
            "Ktor Redis endpoint differs from the proxied endpoint"
        }
        val redisSignal = checkNotNull(application.runtime.redisSignal) {
            "Ktor did not create the Lettuce cancel signal"
        }
        check(redisSignal.isAvailable()) {
            "Ktor cancel signal is not connected through the proxy"
        }
        check(application.dataSource.maximumPoolSize == profile.concurrency.coerceAtLeast(4)) {
            "Ktor Hikari pool limit differs from the selected profile"
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
                if (action == KtorJobConsoleProfileAction.REDIS_KEY_LOSS) {
                    seedOwnedRedisKeys()
                }
            }
        }

    override fun execute(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition =
        when (action) {
            KtorJobConsoleProfileAction.WORKER_RESTART ->
                if (restartStarted.compareAndSet(false, true)) {
                    lifecycleLock.write {
                        runFencedAttemptScenario(restartApplication = true, identity = identity)
                        winners[token.stableOrdinal] = true
                    }
                    WorkloadTerminalDisposition.COMPLETED
                } else {
                    lifecycleLock.read { submitAndComplete(token, identity) }
                }

            KtorJobConsoleProfileAction.SLOW_PROVIDER -> executeSlowProvider(token, identity)

            else -> lifecycleLock.read { executeLiveAction(token, identity) }
        }

    override fun injectDeclaredFailure() {
        when (action) {
            KtorJobConsoleProfileAction.REDIS_PATH_OUTAGE -> injectRedisPathOutage()
            KtorJobConsoleProfileAction.REDIS_KEY_LOSS -> deleteOwnedRedisKeys()
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
            val actionEvidence = when (action) {
                KtorJobConsoleProfileAction.REDIS_PATH_OUTAGE ->
                    redisPathEvidence?.let {
                        ",oldConnectionFailure=${it.oldConnectionFailureObserved}," +
                            "newConnectionFailure=${it.newConnectionFailureObserved}," +
                            "redisRecovered=${it.recovered}," +
                            "durableCancelsDuringOutage=${it.durableCancelsDuringOutage}"
                    }.orEmpty()

                KtorJobConsoleProfileAction.REDIS_KEY_LOSS ->
                    redisKeyLossEvidence?.let {
                        ",deletedOwnedKeys=${it.deletedKeyCount}," +
                            "durableCancelsAfterKeyLoss=${it.durableCancelsAfterKeyLoss}"
                    }.orEmpty()

                KtorJobConsoleProfileAction.SLOW_PROVIDER,
                KtorJobConsoleProfileAction.WORKER_RESTART,
                -> staleAttemptEvidence?.let {
                    ",staleCode=${it.staleErrorCode}," +
                        "serverJobsStopped=${it.serverJobsStopped}," +
                        "oldPortReleased=${it.oldPortReleased}," +
                        "lateEffects=${it.lateEffectCount},lateReceipts=${it.lateReceiptCount}"
                }.orEmpty()

                else -> ""
            }
            "hikari=true,maxPool=${application.dataSource.maximumPoolSize}," +
                "redisProxy=${application.redisUri == topology.redisUri}$actionEvidence"
        }

    override fun assertProfileInvariants(delta: JobConsoleAuthorityBaseline) {
        check(delta.jobs > 0) { "measured workload did not create durable Job authority" }
        check(delta.effects > 0) { "measured workload did not create durable history" }
        check(delta.receipts > 0) { "measured workload did not create durable outbox receipts" }
        check(jobIdsByIdentity.isNotEmpty()) { "measured workload has no live HTTP identities" }
        jobIdsByIdentity.forEach { (identityOrdinal, jobId) ->
            val scenario = scenariosByIdentity.getValue(identityOrdinal)
            val http = application.http.snapshot(jobId, scenario)
            val stored = checkNotNull(application.runtime.repository.load(jobId))
            check(http.state == stored.state.wireValue) {
                "Ktor HTTP snapshot and PostgreSQL authority diverged for job[$jobId]"
            }
        }
        when (action) {
            KtorJobConsoleProfileAction.BURST -> {
                check(delta.jobs == profile.operationCount.toLong())
                check(jobIdsByIdentity.size == profile.operationCount)
                check(burstQueueViolationCount() == 0L)
                check(activeJobCount() == 0L)
                check(queueVersionDivergenceCount() == 0L)
            }

            KtorJobConsoleProfileAction.DUPLICATE_STORM -> {
                check(delta.jobs == profile.contentionShape.identityCount.toLong())
                check(jobIdsByIdentity.size == profile.contentionShape.identityCount)
            }

            KtorJobConsoleProfileAction.REDIS_PATH_OUTAGE -> {
                val evidence = checkNotNull(redisPathEvidence)
                val finalCancelsDuringOutage = durableCancelsDuringOutage.get()
                redisPathEvidence = KtorRedisPathEvidence(
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

            KtorJobConsoleProfileAction.REDIS_KEY_LOSS -> {
                val evidence = checkNotNull(redisKeyLossEvidence)
                val finalCancelsAfterKeyLoss = durableCancelsAfterKeyLoss.get()
                redisKeyLossEvidence = KtorRedisKeyLossEvidence(
                    deletedKeyCount = evidence.deletedKeyCount,
                    durableCancelsAfterKeyLoss = finalCancelsAfterKeyLoss,
                )
                check(evidence.deletedKeyCount == profile.operationCount)
                check(finalCancelsAfterKeyLoss > 0)
            }

            KtorJobConsoleProfileAction.SLOW_PROVIDER,
            KtorJobConsoleProfileAction.WORKER_RESTART,
            -> assertStaleAttemptEvidence()

            KtorJobConsoleProfileAction.DUPLICATE_DELIVERY ->
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
            "stable Ktor HTTP identity resolved to different jobs"
        }

        when (action) {
            KtorJobConsoleProfileAction.REDIS_PATH_OUTAGE -> {
                val beganDuringOutage = outageActive.get()
                application.http.cancel(submitted.jobId, scenario)
                if (beganDuringOutage) durableCancelsDuringOutage.incrementAndGet()
            }

            KtorJobConsoleProfileAction.REDIS_KEY_LOSS -> {
                application.http.cancel(submitted.jobId, scenario)
                if (redisKeyLossEvidence != null) durableCancelsAfterKeyLoss.incrementAndGet()
            }

            KtorJobConsoleProfileAction.DUPLICATE_DELIVERY -> {
                if (winner) {
                    completeThroughOwnedWorker(submitted.jobId)
                    val claim = application.runtime.outboxRepository.claim(16, Duration.ofSeconds(5))
                    claim.events.forEach { event ->
                        check(application.runtime.outboxRepository.markPublished(claim.token, event.eventId))
                        check(application.runtime.outboxRepository.markPublished(claim.token, event.eventId))
                        deliveredEventIds += event.eventId
                    }
                }
            }

            KtorJobConsoleProfileAction.BURST -> {
                if (token.stableOrdinal == 0) application.http.verifyHeartbeat(submitted.jobId, scenario)
                if (winner) completeThroughOwnedWorker(submitted.jobId)
            }

            KtorJobConsoleProfileAction.DUPLICATE_STORM -> {
                if (winner) completeThroughOwnedWorker(submitted.jobId)
            }

            else -> error("profile action[$action] must use its dedicated lifecycle path")
        }
        return WorkloadTerminalDisposition.COMPLETED
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

    private fun executeSlowProvider(
        token: ScheduleToken,
        identity: WorkloadIdentity,
    ): WorkloadTerminalDisposition {
        if (slowProviderStarted.compareAndSet(false, true)) {
            try {
                lifecycleLock.write {
                    runFencedAttemptScenario(restartApplication = false, identity = identity)
                    winners[token.stableOrdinal] = true
                }
                slowProviderReady.complete(Unit)
            } catch (failure: Throwable) {
                slowProviderReady.completeExceptionally(failure)
                throw failure
            }
        } else {
            awaitSlowProviderBoundary()
            lifecycleLock.read {
                submitAndComplete(token, identity)
            }
        }
        return WorkloadTerminalDisposition.COMPLETED
    }

    private fun awaitSlowProviderBoundary() {
        try {
            slowProviderReady.get(profile.workloadJoinDeadlineMs, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "slow-provider lifecycle boundary did not become ready before the workload deadline",
                failure,
            )
        }
    }

    private fun completeThroughOwnedWorker(jobId: UUID) {
        val deadline = System.nanoTime() + Duration.ofMillis(profile.recoveryDeadlineMs).toNanos()
        do {
            val stored = application.runtime.repository.load(jobId)
            if (stored?.state?.terminal == true) return
            application.runtime.workerEngine.runOnce()
        } while (System.nanoTime() < deadline)
        error("application-owned Ktor worker did not complete job[$jobId]")
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
            application.runtime.repository.claimNext(scenario.scope.tenantId, Duration.ofMillis(10)),
        )
        val staleDataSource = createProfileOwnedDataSource()
        val staleRepository = JobRepository(staleDataSource)
        val barrier = JobConsoleBarrier(1)
        val staleExecutor = VirtualThreads.executorService()
        try {
            val staleAttempt = staleExecutor.submit<JobProblemCode> {
                check(barrier.readyAndAwait(Duration.ofMillis(profile.failureDetectionDeadlineMs))) {
                    "stale Ktor attempt barrier timed out"
                }
                try {
                    staleRepository.checkpoint(oldClaim.lease, completedChunk = 1, progress = 100)
                    error("stale Ktor checkpoint unexpectedly committed")
                } catch (failure: JobRepositoryException) {
                    failure.code
                }
            }
            check(barrier.awaitReady(Duration.ofMillis(profile.failureDetectionDeadlineMs))) {
                "stale Ktor attempt did not reach its transaction-free pause"
            }
            val pausedConnections = staleDataSource.hikariPoolMXBean.activeConnections
            val oldLeaseToken = oldClaim.lease.token.toString()
            var serverJobsStopped = true
            var oldPortReleased = true
            if (restartApplication) {
                val oldApplication = application
                closeApplication(oldApplication)
                serverJobsStopped = oldApplication.runtime.backgroundJobsStopped
                oldPortReleased = canBind(oldApplication.port)
                application = startApplication()
            }

            val replacement = awaitReplacementClaim(scenario.scope.tenantId)
            val checkpoint = application.runtime.repository.checkpoint(
                replacement.lease,
                completedChunk = 1,
                progress = 100,
            )
            application.runtime.repository.complete(checkpoint.lease, JobSignal.SUCCESS)
            val converged = application.http.snapshot(submitted.jobId, scenario)
            check(converged.state == "succeeded")
            val beforeRelease = authorityBaseline(submitted.jobId)

            barrier.release()
            val staleCode = staleAttempt.get(profile.recoveryDeadlineMs, TimeUnit.MILLISECONDS)
            check(staleCode == JobProblemCode.LEASE_LOST)
            val afterRelease = authorityBaseline(submitted.jobId)
            staleAttemptEvidence = KtorStaleAttemptEvidence(
                pausedConnections = pausedConnections,
                pausedTransactions = 0,
                pausedLocks = 0,
                oldLeaseToken = oldLeaseToken,
                pendingCompletedChunk = 1,
                staleErrorCode = staleCode,
                serverJobsStopped = serverJobsStopped,
                oldPortReleased = oldPortReleased,
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
            timeout = Duration.ofMillis(profile.recoveryDeadlineMs),
            pollInterval = Duration.ofMillis(10),
            description = "replacement Ktor worker did not reclaim the expired lease",
        ) {
            application.runtime.repository.reclaimExpired(tenantId, Duration.ofSeconds(5))
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
        check(evidence.serverJobsStopped)
        check(evidence.oldPortReleased)
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
            description = "Ktor durable cancel was not observed during the Redis outage",
        ) {
            durableCancelsDuringOutage.get() > 0
        }
        topology.restoreExistingConnections()
        topology.enableNewConnections()
        outageActive.set(false)
        oldConnection.close()
        val recovered = awaitRedisRecovery()
        redisPathEvidence = KtorRedisPathEvidence(
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
                description = "Ktor Redis path did not recover",
            ) {
                topology.openRedisConnection().use { connection ->
                    connection.ping() == "PONG" &&
                        application.runtime.redisSignal?.isAvailable() == true
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
                ).deleteOwnedKeys(OwnedRedisWriterBarrier.NONE)
                redisKeyLossEvidence = KtorRedisKeyLossEvidence(
                    deletedKeyCount = result.deletedKeys.size,
                    durableCancelsAfterKeyLoss = durableCancelsAfterKeyLoss.get(),
                )
            }
        }
    }

    private fun startApplication(): RunningKtorJobConsole {
        val dataSource = createProfileOwnedDataSource("job-ktor-${Uuid.V7.nextId()}")
        val workerGate = CompletableDeferred<Unit>()
        val outboxGate = CompletableDeferred<Unit>()
        var runtime: JobConsoleKtorRuntime? = null
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            jobConsoleModule(
                dataSource = dataSource,
                demoEnabled = true,
                redisUri = topology.redisUri,
                workerEnabled = true,
                outboxStartGate = outboxGate::await,
                workerStartGate = workerGate::await,
                runtimeObserver = { runtime = it },
            )
        }
        return try {
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            RunningKtorJobConsole(
                dataSource = dataSource,
                runtime = checkNotNull(runtime) { "Ktor runtime observer was not invoked" },
                redisUri = topology.redisUri,
                port = port,
                http = KtorJobConsoleHttpClient(URI("http://127.0.0.1:$port")),
                stop = { server.stop(gracePeriodMillis = 0, timeoutMillis = 5_000) },
            )
        } catch (error: Throwable) {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 5_000)
            if (!dataSource.isClosed) dataSource.close()
            throw error
        }
    }

    private fun closeApplication(running: RunningKtorJobConsole) {
        if (!running.dataSource.isClosed) {
            running.stop()
        }
        check(running.dataSource.isClosed) { "Ktor-owned Hikari pool remained active" }
        check(running.runtime.backgroundJobsStopped) { "Ktor application jobs remained active" }
        check(canBind(running.port)) { "Ktor port[${running.port}] remained bound after stop" }
    }

    private fun createProfileOwnedDataSource(
        poolName: String = "job-ktor-stale-attempt",
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = fixture.jdbcUrl
                username = fixture.databaseUsername
                password = fixture.databasePassword
                schema = fixture.schema
                maximumPoolSize = profile.concurrency.coerceAtLeast(4)
                this.poolName = poolName
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

    private fun authorityBaseline(jobId: UUID): JobConsoleAuthorityBaseline =
        application.dataSource.connection.use { connection ->
            JobConsoleAuthorityBaseline(
                jobs = connection.countRows("jobs", jobId),
                effects = connection.countRows("job_history", jobId),
                receipts = connection.countRows("job_outbox", jobId),
            )
        }

    private fun duplicateOutboxLogicalEventCount(): Long =
        scalarCount(
            """
            SELECT count(*)
            FROM (
                SELECT job_id, event_type, resource_version
                FROM job_outbox
                GROUP BY job_id, event_type, resource_version
                HAVING count(*) > 1
            ) duplicates
            """.trimIndent(),
        )

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
        if (action != KtorJobConsoleProfileAction.BURST) {
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
        ): KtorJobConsoleLiveAdapter {
            val validRunId = runId.requireNotBlank("runId")
            val journalRoot = Files.createTempDirectory("job-ktor-high-contention-")
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
                KtorJobConsoleLiveAdapter(
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
                network = dockerResource(runId, profileId, "ktor-redis-network", "network"),
                redis = dockerResource(runId, profileId, "ktor-redis-primary", "container"),
                toxiproxy = dockerResource(runId, profileId, "ktor-redis-toxiproxy", "container"),
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

private class RunningKtorJobConsole(
    val dataSource: HikariDataSource,
    val runtime: JobConsoleKtorRuntime,
    val redisUri: String,
    val port: Int,
    val http: KtorJobConsoleHttpClient,
    val stop: () -> Unit,
)

private class KtorJobConsoleHttpSnapshot(
    val jobId: UUID,
    val state: String,
)

private class KtorJobConsoleHttpClient(
    private val baseUri: URI,
    private val client: HttpClient = HttpClient.newBuilder()
        .proxy(ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) {

    fun submit(scenario: JobConsoleScenario): KtorJobConsoleHttpSnapshot =
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
    ): KtorJobConsoleHttpSnapshot =
        request(
            method = "GET",
            path = "/v1/jobs/$jobId",
            scenario = scenario,
            expectedStatus = 200,
        ).toSnapshot()

    fun cancel(
        jobId: UUID,
        scenario: JobConsoleScenario,
    ): KtorJobConsoleHttpSnapshot =
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
                "Ktor SSE heartbeat was not observed"
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
                "Ktor HTTP $method $path returned ${response.statusCode()}: ${response.body()}"
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

    private fun HttpResponse<String>.toSnapshot(): KtorJobConsoleHttpSnapshot {
        val jobId = JOB_ID.find(body())?.groupValues?.get(1)
            ?: error("Ktor response omitted jobId")
        val state = STATE.find(body())?.groupValues?.get(1)
            ?: error("Ktor response omitted state")
        return KtorJobConsoleHttpSnapshot(UUID.fromString(jobId), state)
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

private fun java.sql.Connection.countRows(table: String, jobId: UUID): Long =
    prepareStatement("SELECT count(*) FROM $table WHERE job_id = ?").use { statement ->
        statement.setObject(1, jobId)
        statement.executeQuery().use { result ->
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

private fun canBind(port: Int): Boolean =
    try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    } catch (_: Exception) {
        false
    }

private fun deleteDirectory(directory: Path) {
    Files.list(directory).use { paths ->
        paths.forEach(Files::deleteIfExists)
    }
    Files.deleteIfExists(directory)
}
