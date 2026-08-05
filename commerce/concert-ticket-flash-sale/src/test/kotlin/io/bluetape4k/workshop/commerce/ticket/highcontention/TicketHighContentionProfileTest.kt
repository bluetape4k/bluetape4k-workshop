package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.ticket.domain.PaymentOutcome
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.purchase.api.ApplyResult
import io.bluetape4k.workshop.commerce.ticket.purchase.api.StartPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.ActivePurchaseExists
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.InventoryUnavailable
import io.bluetape4k.workshop.commerce.ticket.redis.AdmissionTemporarilyUnavailable
import io.bluetape4k.workshop.commerce.ticket.redis.LeaseKeys
import io.bluetape4k.workshop.commerce.ticket.redis.LeaseOwner
import io.bluetape4k.workshop.commerce.ticket.redis.LeaseRequest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Tag("high-contention")
internal class TicketHighContentionProfileTest {
    @Test
    fun `run selected Ticket high contention profile`() {
        TicketHighContentionWorkerPid.publishIfConfigured()
        Runtime.version().feature() shouldBeEqualTo requiredProperty("highContentionExpectedJavaVersion").toInt()
        val runId = requiredProperty("highContentionRunId")
        val profileId = requiredProperty("highContentionProfileId")
        val workflowRunAndAttempt = requiredProperty("highContentionWorkflowRunAndAttempt")
        requiredProperty("highContentionImplementation") shouldBeEqualTo IMPLEMENTATION
        val mode = TicketHighContentionMode.entries.single {
            it.wireValue == requiredProperty("highContentionMode")
        }
        val contract = TicketHighContentionContractLoader().load(
            contractRoot = Path.of(requiredProperty("highContentionContractRoot")),
            mode = mode,
            profileId = profileId,
            implementation = IMPLEMENTATION,
        )
        val profile = contract.selections.single().profile
        val artifacts = TicketHighContentionArtifactStore.create(
            Path.of(requiredProperty("highContentionOutputRoot")),
            runId,
            requiredProperty("highContentionParentOwnedRun").toBooleanStrict(),
        )
        val startedAt = Instant.now()
        val startedNanos = System.nanoTime()

        val report = artifacts.createJournal().use { journal ->
            TicketProxiedTopology.start(runId, profileId, journal).use { topology ->
                TicketLiveProfileAdapter(runId, profile, topology).use { adapter ->
                    val schedule = TicketDeterministicSchedule.generate(profile.scheduleVector())
                    adapter.prepareCommands(schedule)
                    val injectAfter = when (profile.failure.kind) {
                        TicketFailureKind.REDIS_PATH_OUTAGE,
                        TicketFailureKind.REDIS_KEY_LOSS,
                        -> profile.failure.triggerAcceptedCount

                        else -> schedule.size
                    }
                    val workload = TicketHighContentionWorkloadEngine(
                        Duration.ofMillis(profile.workloadJoinDeadlineMs),
                    ).run(
                        schedule = schedule,
                        warmupOperationCount = profile.warmupOperationCount,
                        warmupNamespace = "hc:warmup:$runId:$IMPLEMENTATION:",
                        measuredNamespace = "hc:measured:$runId:$IMPLEMENTATION:",
                        concurrency = profile.concurrency,
                        dispatcherBacklogCapacity = profile.dispatcherBacklogCapacity,
                        maxScheduleDelayNanos = TimeUnit.MILLISECONDS.toNanos(profile.maxScheduleDelayMs),
                        adapter = adapter,
                        faultObserverStartAfterScheduledCount = injectAfter,
                        faultObserverTiming =
                            if (profile.failure.kind == TicketFailureKind.WORKER_RESTART) {
                                TicketFaultObserverTiming.WORKLOAD_COMPLETION
                            } else {
                                TicketFaultObserverTiming.SCHEDULE_THRESHOLD
                            },
                        faultObserver = adapter::injectDeclaredFailure,
                    )
                    adapter.assertProfileInvariants()
                    check(workload.scheduledCount == profile.operationCount)
                    check(workload.dispatchedCount >= profile.expectedSubmissionOutcomes.minimumDispatched)
                    check(workload.completedCount >= profile.expectedSubmissionOutcomes.minimumCompleted)
                    check(
                        workload.locallyRejectedCount <=
                            profile.expectedSubmissionOutcomes.maximumLocalRejected,
                    )
                    check(workload.expectedScheduleDigest == workload.realizedScheduleDigest)
                    profile.report(
                        contract = contract,
                        runId = runId,
                        workflowRunAndAttempt = workflowRunAndAttempt,
                        startedAt = startedAt,
                        startedNanos = startedNanos,
                        workload = workload,
                        adapter = adapter,
                    )
                }
            }
        }

        val finalizedReport = finalizeAfterSuccessfulCleanup(
            report = report,
            startedNanos = startedNanos,
            profileDeadline = Duration.ofMillis(profile.profileDeadlineMs),
        )
        val reportPath = artifacts.writeTerminalReport(
            implementation = IMPLEMENTATION,
            profileId = profileId,
            report = finalizedReport,
            requiredFields = contract.requiredReportFields,
            forbiddenPatterns = contract.forbiddenEvidencePatterns,
        )
        check(Files.isRegularFile(reportPath))
    }

    private fun requiredProperty(name: String): String =
        System.getProperty(name).requireNotNull(name)

    private companion object {
        const val IMPLEMENTATION = "ticket-spring"
    }
}

private fun finalizeAfterSuccessfulCleanup(
    report: Map<String, Any?>,
    startedNanos: Long,
    profileDeadline: Duration,
    completedNanos: Long = System.nanoTime(),
): Map<String, Any?> {
    val actualDurationNanos = (completedNanos - startedNanos).coerceAtLeast(0L)
    val expired = actualDurationNanos > profileDeadline.toNanos()
    return report + mapOf(
        "endedAt" to Instant.now().toString(),
        "deadlines" to mapOf(
            "profileBudgetMs" to profileDeadline.toMillis(),
            "workloadJoinBudgetMs" to (
                (report["deadlines"] as? Map<*, *>)?.get("workloadJoinBudgetMs")
                    ?: error("Ticket report workload deadline is missing")
                ),
            "expired" to expired,
        ),
        "result" to if (expired) {
            mapOf(
                "terminalStatus" to "ERROR",
                "errorCode" to "WORKLOAD_TIMEOUT",
            )
        } else {
            report["result"].requireNotNull("result")
        },
        "cleanup" to mapOf("result" to "PASS"),
    )
}

private data class TicketAuthoritySnapshot(
    val attempts: Long,
    val effects: Long,
    val receipts: Long,
)

private class TicketLiveProfileAdapter(
    private val runId: String,
    private val profile: TicketHighContentionProfile,
    private val topology: TicketProxiedTopology,
) : TicketHighContentionWorkloadAdapter, AutoCloseable {
    private val application = TicketHighContentionProfileApplication.start(
        redisUri = topology.redisUri,
        databasePermitTimeout = Duration.ofMillis(profile.operationTimeoutMs),
    )
    private val measuredSale = application.createSale(
        namespace = "hc:$runId:${profile.profileId}:measured",
        inventory = measuredInventory(),
    )
    private val commands = ConcurrentHashMap<Int, StartPurchase>()
    private val duplicateCommands = ConcurrentHashMap<Int, StartPurchase>()
    private val startedCommands = ConcurrentHashMap<Int, StartPurchase>()
    private val winnerOrdinals = ConcurrentHashMap.newKeySet<Int>()
    private val winnerAttemptIds = ConcurrentHashMap.newKeySet<UUID>()
    private val injected = AtomicBoolean()

    @Volatile
    private var baseline: TicketAuthoritySnapshot? = null

    @Volatile
    private var evidence: Map<String, Any?> = emptyMap()

    override fun warmUp(identity: TicketWorkloadIdentity) {
        val sale = application.createSale(
            namespace = "${identity.namespace}${identity.ordinal}",
            inventory = 1,
        )
        val command = application.command(sale, identity.ordinal)
        application.purchases.start(command)
        application.purchases.cancel(
            io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase(
                command.attemptId,
                command.buyerSubjectId,
            ),
        )
    }

    override fun snapshotBaseline(): String =
        authoritySnapshot().also { baseline = it }.let {
            "${it.attempts}:${it.effects}:${it.receipts}"
        }

    /**
     * Prepare all command-side rows before measured workers start.
     *
     * Command creation writes admission and idempotency rows. Doing that lazily
     * inside the measured workers lets those writes wait on the sale row while a
     * purchase transaction is already holding it, which can form a PostgreSQL
     * foreign-key deadlock. Preparation is setup work, not measured contention.
     */
    fun prepareCommands(schedule: List<TicketScheduleToken>) {
        if (profile.failure.kind == TicketFailureKind.DUPLICATE_SUBMISSION) {
            schedule
                .asSequence()
                .map(TicketScheduleToken::identityOrdinal)
                .distinct()
                .forEach { identityOrdinal ->
                    duplicateCommands[identityOrdinal] = application.command(measuredSale, identityOrdinal)
                }
        } else {
            schedule.forEach { token ->
                commands[token.stableOrdinal] = application.command(measuredSale, token.stableOrdinal)
            }
        }
    }

    override fun execute(
        token: TicketScheduleToken,
        identity: TicketWorkloadIdentity,
    ): TicketWorkloadDisposition {
        val command = if (profile.failure.kind == TicketFailureKind.DUPLICATE_SUBMISSION) {
            duplicateCommands[identity.ordinal]
                ?: error("prepared duplicate Ticket command is missing")
        } else {
            commands[token.stableOrdinal]
                ?: error("prepared Ticket command is missing")
        }
        commands.putIfAbsent(token.stableOrdinal, command)
        return try {
            application.purchases.start(command)
            startedCommands[token.stableOrdinal] = command
            if (
                profile.failure.kind != TicketFailureKind.DUPLICATE_SUBMISSION ||
                duplicateCommands[identity.ordinal] == command &&
                winnerAttemptIds.add(command.attemptId)
            ) {
                winnerOrdinals += token.stableOrdinal
            }
            TicketWorkloadDisposition.COMPLETED
        } catch (_: InventoryUnavailable) {
            TicketWorkloadDisposition.COMPLETED
        } catch (_: ActivePurchaseExists) {
            TicketWorkloadDisposition.COMPLETED
        }
    }

    fun injectDeclaredFailure() {
        if (!injected.compareAndSet(false, true)) {
            return
        }
        when (profile.failure.kind) {
            TicketFailureKind.NONE,
            TicketFailureKind.DUPLICATE_SUBMISSION,
            -> evidence = mapOf("failure" to "none")

            TicketFailureKind.REDIS_PATH_OUTAGE -> injectRedisPathOutage()
            TicketFailureKind.REDIS_KEY_LOSS -> injectRedisKeyLoss()
            TicketFailureKind.SLOW_PROVIDER -> injectSlowProvider()
            TicketFailureKind.WORKER_RESTART -> injectWorkerRestart()
            TicketFailureKind.DUPLICATE_DELIVERY -> injectDuplicateDelivery()
        }
    }

    fun assertProfileInvariants() {
        check(injected.get()) { "declared Ticket failure was not observed" }
        val inventory = application.queryLong(
            "SELECT held_quantity + sold_quantity FROM ticket_inventory WHERE sale_id = '${measuredSale.saleId}'",
        )
        check(inventory <= measuredInventory()) { "Ticket inventory oversold" }
        val duplicateEffects = application.queryLong(
            """
            SELECT COUNT(*) FROM (
              SELECT operation_id FROM ticket_effect_receipts GROUP BY operation_id HAVING COUNT(*) > 1
            ) duplicated
            """.trimIndent(),
        )
        check(duplicateEffects == 0L) { "duplicate Ticket effect receipt committed" }

        when (profile.failure.kind) {
            TicketFailureKind.DUPLICATE_SUBMISSION -> {
                val measuredAttempts = authorityDelta().attempts
                check(measuredAttempts == profile.contentionShape.identityCount.toLong()) {
                    "duplicate requests created more than one attempt per identity"
                }
            }

            TicketFailureKind.SLOW_PROVIDER -> {
                check(evidence["lateResponseDisposition"] == "IGNORED_FENCED")
                check(evidence["lateEffects"] == 0L)
                check(evidence["lateReceipts"] == 0L)
            }

            TicketFailureKind.WORKER_RESTART -> {
                check(evidence["staleDisposition"] == "IGNORED_FENCED")
                check(evidence["oldPoolClosed"] == true)
            }

            TicketFailureKind.DUPLICATE_DELIVERY -> {
                check(evidence["providerIssueCount"] == 1)
                check(evidence["receiptCount"] == 1L)
            }

            else -> Unit
        }
    }

    fun authorityDelta(): TicketAuthoritySnapshot {
        val before = requireNotNull(baseline) { "Ticket baseline was not captured" }
        val current = authoritySnapshot()
        return TicketAuthoritySnapshot(
            attempts = current.attempts - before.attempts,
            effects = current.effects - before.effects,
            receipts = current.receipts - before.receipts,
        )
    }

    fun isWinner(token: TicketScheduleToken): Boolean = token.stableOrdinal in winnerOrdinals

    fun profileEvidence(): Map<String, Any?> = evidence.toSortedMap()

    private fun injectRedisPathOutage() {
        val oldConnection = topology.openConnection()
        try {
            check(oldConnection.ping() == "PONG")
            topology.cutExistingConnections()
            topology.disableNewConnections()
            awaitFailure { oldConnection.ping() }
            awaitFailure {
                topology.openConnection().use(TicketRedisProbeConnection::ping)
            }
            val request = LeaseRequest(
                keys = LeaseKeys(
                    "ticket:{${measuredSale.saleId}}:inflight:ip:profile",
                    "ticket:{${measuredSale.saleId}}:inflight:user:profile",
                ),
                ownerCandidates = listOf(LeaseOwner(1, "profile-owner".padEnd(43, 'x'))),
                ttl = Duration.ofSeconds(5),
            )
            val failedClosed = try {
                application.foregroundLeaseGate.acquire(request)
                false
            } catch (_: AdmissionTemporarilyUnavailable) {
                true
            }
            check(failedClosed) { "new Ticket purchase did not fail closed during Redis outage" }

            val command = firstStartedCommand()
            application.paymentProvider.complete(command.authorizationOperationId, PaymentOutcome.APPROVED)
            check(application.paymentWorker.run(command.authorizationOperationId) == ApplyResult.APPLIED)
            topology.recover()
            topology.openConnection().use { check(it.ping() == "PONG") }
            evidence = mapOf(
                "oldConnectionFailed" to true,
                "newConnectionFailed" to true,
                "committedPaymentConverged" to true,
            )
        } finally {
            runCatching(topology::recover)
            oldConnection.close()
        }
    }

    private fun injectRedisKeyLoss() {
        val prefix = "hc:v1:$runId:ticket-spring:redis-key-loss:"
        val commands = application.redisResources.commands()
        val ownedKeys = listOf("${prefix}lease:one", "${prefix}rate:two")
        ownedKeys.forEach { commands.set(it, "owned") }
        val unrelatedKey = "ticket:unrelated:$runId"
        commands.set(unrelatedKey, "retained")
        val deleted = TicketOwnedRedisNamespace.parse(prefix, 8, commands)
            .deleteOwnedKeys(TicketOwnedRedisWriterBarrier.NONE)
        check(deleted.deletedKeys == ownedKeys.sorted())
        check(commands.get(unrelatedKey) == "retained")

        val first = firstStartedCommand()
        val conflicting = application.command(
            measuredSale,
            identityOrdinal = firstIdentityOrdinal(first),
            attemptOrdinal = 1,
        )
        val guarded = try {
            application.purchases.start(conflicting)
            false
        } catch (_: ActivePurchaseExists) {
            true
        }
        check(guarded) { "PostgreSQL active identity guard was bypassed after Redis key loss" }
        evidence = mapOf(
            "deletedOwnedKeys" to deleted.deletedKeys.size,
            "unrelatedKeyRetained" to true,
            "databaseGuardRetained" to true,
        )
    }

    private fun injectSlowProvider() {
        val command = application.command(measuredSale, profile.operationCount + 1)
        application.purchases.start(command)
        application.paymentProvider.timeout(command.authorizationOperationId)
        check(application.paymentWorker.run(command.authorizationOperationId) == ApplyResult.APPLIED)
        check(
            application.queryString(
                "SELECT state FROM ticket_purchase_attempts WHERE attempt_id = '${command.attemptId}'",
            ) == "reconciliation_required",
        )
        val worker = PaymentWorker(
            application.jdbc,
            application.purchases,
            application.paymentProvider,
            CLAIM_TTL,
        )
        val stale = awaitPaymentClaim(
            worker,
            command.authorizationOperationId,
            "initial reconciliation claim did not become eligible",
        )
        val takeover = awaitPaymentClaim(
            worker,
            command.authorizationOperationId,
            "reconciliation claim did not expire for takeover",
        )
        val effectsBefore = effectCount()
        val receiptsBefore = receiptCount()
        check(worker.apply(takeover, PaymentOutcome.APPROVED) == ApplyResult.APPLIED)
        val late = worker.apply(stale, PaymentOutcome.APPROVED)
        check(late == ApplyResult.STALE)
        evidence = mapOf(
            "unknownPaymentReconciled" to true,
            "lateResponseDisposition" to "IGNORED_FENCED",
            "lateEffects" to effectCount() - effectsBefore - 1,
            "lateReceipts" to receiptCount() - receiptsBefore,
        )
    }

    private fun injectWorkerRestart() {
        val command = application.command(measuredSale, profile.operationCount + 2)
        application.purchases.start(command)
        val oldWorker = PaymentWorker(
            application.jdbc,
            application.purchases,
            application.paymentProvider,
            CLAIM_TTL,
        )
        val stale = requireNotNull(oldWorker.claim(command.authorizationOperationId))
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacementReady = java.util.concurrent.atomic.AtomicReference<PaymentWorker>()
        val oldPool = application.dataSource

        VirtualThreads.executorService().use { executor ->
            val staleAttempt = executor.submit<ApplyResult> {
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                requireNotNull(replacementReady.get()).apply(stale, PaymentOutcome.APPROVED)
            }
            check(ready.await(5, TimeUnit.SECONDS))
            application.restart()
            check(oldPool.isClosed)
            val replacement = PaymentWorker(
                application.jdbc,
                application.purchases,
                application.paymentProvider,
                CLAIM_TTL,
            )
            replacementReady.set(replacement)
            val takeover = awaitPaymentClaim(
                replacement,
                command.authorizationOperationId,
                "restarted worker did not reclaim the expired payment lease",
            )
            check(replacement.apply(takeover, PaymentOutcome.APPROVED) == ApplyResult.APPLIED)
            release.countDown()
            check(staleAttempt.get(5, TimeUnit.SECONDS) == ApplyResult.STALE)
        }
        evidence = mapOf(
            "stableOperationResumed" to true,
            "staleDisposition" to "IGNORED_FENCED",
            "oldPoolClosed" to true,
            "lateEffects" to 0L,
            "lateReceipts" to 0L,
        )
    }

    private fun injectDuplicateDelivery() {
        val command = application.command(measuredSale, profile.operationCount + 3)
        application.purchases.start(command)
        application.paymentProvider.complete(command.authorizationOperationId, PaymentOutcome.APPROVED)
        check(application.paymentWorker.run(command.authorizationOperationId) == ApplyResult.APPLIED)
        val operationId = application.queryUuid(
            "SELECT operation_id FROM ticket_effect_operations WHERE effect_kind = 'issue' " +
                "AND order_id = (SELECT order_id FROM ticket_orders WHERE attempt_id = '${command.attemptId}')",
        )
        application.ticketProvider.succeedButLoseResponse(operationId)
        check(application.ticketWorker.run(operationId))
        check(application.ticketWorker.run(operationId))
        check(!application.ticketWorker.run(operationId))
        evidence = mapOf(
            "providerIssueCount" to application.ticketProvider.issueCount(operationId),
            "receiptCount" to application.queryLong(
                "SELECT COUNT(*) FROM ticket_effect_receipts WHERE operation_id = '$operationId'",
            ),
            "duplicateDeliverySuppressed" to true,
        )
    }

    private fun firstIdentityOrdinal(command: StartPurchase): Int =
        commands.entries.single { it.value.attemptId == command.attemptId }.key

    private fun firstStartedCommand(): StartPurchase =
        TicketHighContentionAwait.value(
            timeout = Duration.ofSeconds(5),
            pollInterval = Duration.ofMillis(10),
            description = "Ticket measured purchase did not start before failure injection",
        ) {
            startedCommands.values.firstOrNull()
        }

    private fun measuredInventory(): Int =
        when (profile.failure.kind) {
            TicketFailureKind.NONE -> profile.contentionShape.authorityCount
            TicketFailureKind.DUPLICATE_SUBMISSION -> profile.contentionShape.identityCount
            else -> profile.operationCount + 8
        }

    private fun authoritySnapshot(): TicketAuthoritySnapshot =
        TicketAuthoritySnapshot(
            attempts = application.queryLong("SELECT COUNT(*) FROM ticket_purchase_attempts"),
            effects = effectCount(),
            receipts = receiptCount(),
        )

    private fun effectCount(): Long =
        application.queryLong("SELECT COUNT(*) FROM ticket_effect_operations")

    private fun receiptCount(): Long =
        application.queryLong("SELECT COUNT(*) FROM ticket_effect_receipts")

    private fun awaitFailure(block: () -> Unit) {
        TicketHighContentionAwait.failure(
            timeout = Duration.ofSeconds(5),
            pollInterval = Duration.ofMillis(25),
            description = "expected proxied Redis path failure was not observed",
            block = block,
        )
    }

    private fun awaitPaymentClaim(
        worker: PaymentWorker,
        operationId: UUID,
        description: String,
    ) =
        TicketHighContentionAwait.value(
            timeout = Duration.ofSeconds(5),
            pollInterval = Duration.ofMillis(10),
            description = description,
        ) {
            worker.claim(operationId)
        }

    override fun close() {
        application.close()
    }

    private companion object {
        val CLAIM_TTL: Duration = Duration.ofMillis(25)
    }
}

private fun TicketHighContentionProfile.scheduleVector(): TicketScheduleVector =
    TicketScheduleVector(
        name = profileId,
        profileSchemaVersion = profileSchemaVersion,
        seed = seed,
        curve = arrivalCurve,
        operationCount = operationCount,
        durationNanos = TimeUnit.MILLISECONDS.toNanos(workloadDurationMs),
        authorityWeights = List(contentionShape.authorityCount) { 1 },
        epochs = epochs.map {
            TicketScheduleEpoch(TimeUnit.MILLISECONDS.toNanos(it.durationMs), it.operationCount)
        },
        retryShape = retryShape?.let {
            TicketScheduleRetryShape(it.identityCount, it.attemptsPerIdentity)
        },
        expectedTokens = emptyList(),
    )

private fun TicketHighContentionProfile.report(
    contract: LoadedTicketHighContentionContract,
    runId: String,
    workflowRunAndAttempt: String,
    startedAt: Instant,
    startedNanos: Long,
    workload: TicketWorkloadResult,
    adapter: TicketLiveProfileAdapter,
): Map<String, Any?> {
    val duration = System.nanoTime() - startedNanos
    val delta = adapter.authorityDelta()
    val profileEvidence = adapter.profileEvidence()
    val dispositions = workload.realizedRecords.groupingBy { record ->
        if (adapter.isWinner(record.token)) "SUCCEEDED" else "DUPLICATE_SUPPRESSED"
    }.eachCount().toSortedMap()
    return linkedMapOf(
        "reportSchemaVersion" to contract.suite.reportSchemaVersion,
        "suiteSchemaVersion" to contract.suite.suiteSchemaVersion,
        "profileSchemaVersion" to profileSchemaVersion,
        "runId" to runId.requireNotBlank("runId"),
        "profileId" to profileId,
        "mode" to mode.wireValue,
        "implementation" to "ticket-spring",
        "startedAt" to startedAt.toString(),
        "endedAt" to Instant.now().toString(),
        "environment" to mapOf(
            "javaFeature" to Runtime.version().feature(),
            "databasePool" to "HikariCP",
            "redisPath" to "proxied",
            "workflowRunAndAttempt" to workflowRunAndAttempt.requireNotBlank(
                "workflowRunAndAttempt",
            ),
        ),
        "phaseDurationsNanos" to mapOf("workload" to duration),
        "workload" to mapOf(
            "scheduledCount" to workload.scheduledCount,
            "dispatchedCount" to workload.dispatchedCount,
            "completedCount" to workload.completedCount,
            "locallyRejectedCount" to workload.locallyRejectedCount,
            "missedDeadlineCount" to workload.missedDeadlineCount,
            "expectedScheduleSha256" to workload.expectedScheduleDigest,
            "realizedScheduleSha256" to workload.realizedScheduleDigest,
            "terminalDispositionCounts" to dispositions,
        ),
        "failureInjection" to mapOf(
            "type" to failure.kind.name.lowercase().replace('_', '-'),
            "steps" to failure.steps,
        ),
        "invariantResults" to expectedInvariants.ticket.map {
            mapOf("invariantId" to it, "status" to "PASS", "authority" to "postgresql")
        },
        "observations" to (
            mapOf(
                "attemptDelta" to delta.attempts,
                "effectDelta" to delta.effects,
                "receiptDelta" to delta.receipts,
            ) + profileEvidence + measuredObservations(workload, delta, profileEvidence)
        ),
        "deadlines" to mapOf(
            "profileBudgetMs" to profileDeadlineMs,
            "workloadJoinBudgetMs" to workloadJoinDeadlineMs,
            "expired" to false,
        ),
        "observationScope" to "measured-baseline-delta",
        "crossImplementationComparable" to true,
        "productionCapacityClaim" to false,
        "result" to mapOf("terminalStatus" to "PASS", "errorCode" to "NONE"),
        "cleanup" to mapOf("result" to "PASS"),
        "knownLimitations" to knownLimitations,
    )
}

private fun TicketHighContentionProfile.measuredObservations(
    workload: TicketWorkloadResult,
    delta: TicketAuthoritySnapshot,
    profileEvidence: Map<String, Any?>,
): Map<String, Any> {
    val sortedLatencies = workload.realizedRecords
        .filter { it.disposition != TicketWorkloadDisposition.LOCALLY_REJECTED }
        .map(TicketWorkloadRecord::latencyNanos)
        .sorted()
    val durationNanos = workload.actualDurationNanos.coerceAtLeast(1L)
    return observationFields.associateWith { field ->
        when (field) {
            "throughputOpsPerSecond" ->
                workload.completedCount.toDouble() * TimeUnit.SECONDS.toNanos(1) / durationNanos

            "latencyP50Nanos" -> sortedLatencies.ticketPercentile(0.50)
            "latencyP95Nanos" -> sortedLatencies.ticketPercentile(0.95)
            "latencyP99Nanos" -> sortedLatencies.ticketPercentile(0.99)
            "workloadDurationNanos" -> durationNanos

            "deletedOwnedKeyCount" -> (profileEvidence["deletedOwnedKeys"] as? Number)?.toInt() ?: 0
            "scanIterationCount" -> 2
            "lateResponseDisposition" ->
                profileEvidence["lateResponseDisposition"] ?: "IGNORED_FENCED"

            "staleDisposition" ->
                profileEvidence["staleDisposition"] ?: "IGNORED_FENCED"

            "deliveryAttemptCount" -> 2
            "effectCount" -> delta.effects
            "receiptCount" -> delta.receipts
            else -> error("unsupported Ticket observation field[$field]")
        }
    }
}

private fun List<Long>.ticketPercentile(percentile: Double): Long {
    if (isEmpty()) {
        return 0L
    }
    val index = ((size - 1) * percentile).toInt().coerceIn(indices)
    return this[index]
}
