package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

interface JobConsoleLiveProfileAdapter : HighContentionWorkloadAdapter, AutoCloseable {
    fun injectDeclaredFailure()

    fun authorityDelta(): JobConsoleAuthorityBaseline

    fun winner(token: ScheduleToken): Boolean

    fun profileEvidence(): String

    fun assertProfileInvariants(delta: JobConsoleAuthorityBaseline)
}

class JobConsoleLiveProfileRunner(
    implementation: String,
    private val adapterFactory: (String, HighContentionProfile) -> JobConsoleLiveProfileAdapter,
) {
    private val implementation = implementation.requireNotBlank("implementation")

    fun run() {
        HighContentionWorkerPid.publishIfConfigured()
        Runtime.version().feature().shouldBeEqualTo(
            requiredProperty("highContentionExpectedJavaVersion").toInt(),
        )
        val runId = requiredProperty("highContentionRunId")
        val profileId = requiredProperty("highContentionProfileId")
        requiredProperty("highContentionImplementation").shouldBeEqualTo(implementation)
        val mode = HighContentionMode.entries.single {
            it.wireValue == requiredProperty("highContentionMode")
        }
        val contract = HighContentionContractLoader().load(
            contractRoot = Path.of(requiredProperty("highContentionContractRoot")),
            mode = mode,
            profileId = profileId,
            implementation = implementation,
        )
        val profile = contract.selections.single().profile
        val schedule = DeterministicSchedule.generate(profile.jobConsoleScheduleVector())
        val startedAt = Instant.now()
        val startedNanos = System.nanoTime()

        val report = adapterFactory(runId, profile).use { adapter ->
            val failureObserverStart = when (profile.failure.kind) {
                FailureKind.REDIS_PATH_OUTAGE,
                FailureKind.REDIS_KEY_LOSS,
                -> profile.failure.triggerAcceptedCount

                else -> schedule.size
            }
            val workload = HighContentionWorkloadEngine(
                workloadJoinTimeout = java.time.Duration.ofMillis(profile.workloadJoinDeadlineMs),
            ).run(
                schedule = schedule,
                warmupOperationCount = profile.warmupOperationCount,
                warmupNamespace = "hc:warmup:$runId:$implementation:",
                measuredNamespace = "hc:measured:$runId:$implementation:",
                concurrency = profile.concurrency,
                dispatcherBacklogCapacity = profile.dispatcherBacklogCapacity,
                maxScheduleDelayNanos = TimeUnit.MILLISECONDS.toNanos(profile.maxScheduleDelayMs),
                adapter = adapter,
                faultObserverStartAfterScheduledCount = failureObserverStart,
                faultObserver = adapter::injectDeclaredFailure,
            )
            workload.scheduledCount.shouldBeEqualTo(profile.operationCount)
            workload.dispatchedCount.shouldBeGreaterThan(profile.expectedSubmissionOutcomes.minimumDispatched - 1)
            workload.completedCount.shouldBeGreaterThan(profile.expectedSubmissionOutcomes.minimumCompleted - 1)
            workload.locallyRejectedCount.shouldBeEqualTo(0)
            workload.expectedScheduleDigest.shouldBeEqualTo(workload.realizedScheduleDigest)

            val delta = adapter.authorityDelta()
            val expectedJobs = if (profile.arrivalCurve == ArrivalCurve.RETRY_STORM) {
                profile.contentionShape.identityCount.toLong()
            } else {
                profile.operationCount.toLong()
            }
            delta.jobs.shouldBeEqualTo(expectedJobs)
            delta.effects.shouldBeGreaterThan(0L)
            delta.receipts.shouldBeGreaterThan(0L)
            adapter.assertProfileInvariants(delta)

            profile.jobConsoleReport(
                contract = contract,
                runId = runId,
                implementation = implementation,
                startedAt = startedAt,
                startedNanos = startedNanos,
                workload = workload,
                adapter = adapter,
                delta = delta,
            )
        }
        report.validate()
        val reportPath = HighContentionArtifactStore.create(
            outputRoot = Path.of(requiredProperty("highContentionOutputRoot")),
            runId = runId,
            parentOwnedRun = requiredProperty("highContentionParentOwnedRun").toBooleanStrict(),
        ).writeTerminalReport(
            implementation = implementation,
            profileId = profileId,
            report = report,
        )

        Files.isRegularFile(reportPath).shouldBeEqualTo(true)
    }

    private fun requiredProperty(name: String): String =
        System.getProperty(name).requireNotNull(name)
}

private fun HighContentionProfile.jobConsoleScheduleVector(): ScheduleVector =
    ScheduleVector(
        name = profileId,
        profileSchemaVersion = profileSchemaVersion,
        seed = seed,
        curve = arrivalCurve,
        operationCount = operationCount,
        durationNanos = TimeUnit.MILLISECONDS.toNanos(workloadDurationMs),
        authorityWeights = List(contentionShape.authorityCount) { 1 },
        epochs = epochs.map {
            ScheduleEpoch(
                durationNanos = TimeUnit.MILLISECONDS.toNanos(it.durationMs),
                operationCount = it.operationCount,
            )
        },
        retryShape = retryShape?.let {
            ScheduleRetryShape(
                identityCount = it.identityCount,
                attemptsPerIdentity = it.attemptsPerIdentity,
            )
        },
        expectedTokens = emptyList(),
    )

private fun HighContentionProfile.jobConsoleReport(
    contract: LoadedHighContentionContract,
    runId: String,
    implementation: String,
    startedAt: Instant,
    startedNanos: Long,
    workload: HighContentionWorkloadResult,
    adapter: JobConsoleLiveProfileAdapter,
    delta: JobConsoleAuthorityBaseline,
): HighContentionTerminalReport {
    val endedAt = Instant.now()
    val actualDurationNanos = System.nanoTime() - startedNanos
    val attempts = workload.realizedRecords.map { record ->
        val winner = adapter.winner(record.token)
        HighContentionAttemptEvidence(
            stableOrdinal = record.token.stableOrdinal,
            identityOrdinal = record.token.identityOrdinal,
            attemptOrdinal = record.token.attemptOrdinal,
            terminalDisposition = if (winner) {
                HighContentionTerminalDisposition.SUCCEEDED
            } else {
                HighContentionTerminalDisposition.DUPLICATE_SUPPRESSED
            },
            failurePoint = HighContentionFailurePoint.NONE,
            authorityWinnerCount = if (winner) 1 else 0,
            effectWinnerCount = if (winner) 1 else 0,
            receiptWinnerCount = if (winner) 1 else 0,
        )
    }
    val attemptsByIdentity = attempts.groupBy(HighContentionAttemptEvidence::identityOrdinal)
    val dispositionCounts = HighContentionTerminalDisposition.entries.associateWith { disposition ->
        attempts.count { it.terminalDisposition == disposition }
    }
    val invariantIds = expectedInvariants.job.map { it.lowercase().replace('_', '-') }
    val boundaryEvidence = adapter.profileEvidence()

    return HighContentionTerminalReport(
        reportSchemaVersion = contract.suite.reportSchemaVersion,
        suiteSchemaVersion = contract.suite.suiteSchemaVersion,
        profileSchemaVersion = profileSchemaVersion,
        runId = runId,
        profileId = profileId,
        mode = mode,
        implementation = implementation,
        startedAt = startedAt,
        endedAt = endedAt,
        environment = HighContentionEnvironmentEvidence(
            sourceCommit = "local-worktree",
            sourceDirty = true,
            sourceReproducible = false,
        ),
        phaseDurationsNanos = mapOf("workload" to actualDurationNanos),
        workload = HighContentionWorkloadEvidence(
            effectiveConfiguration = HighContentionEffectiveConfiguration(
                operationCount = operationCount,
                concurrency = concurrency,
                dispatcherBacklogCapacity = dispatcherBacklogCapacity,
                warmupOperationCount = warmupOperationCount,
            ),
            schedule = HighContentionScheduleEvidence(
                expectedTokenCount = workload.expectedTokenCount,
                expectedScheduleSha256 = workload.expectedScheduleDigest,
                realizedTokenManifestSha256 = workload.realizedScheduleDigest,
            ),
            submissionConservation = HighContentionSubmissionConservation(
                scheduledCount = workload.scheduledCount,
                dispatchedCount = workload.dispatchedCount,
                completedCount = workload.completedCount,
                cancelledCount = workload.cancelledCount,
                timedOutCount = workload.timedOutCount,
                locallyRejectedCount = workload.locallyRejectedCount,
                terminalDispositionCounts = dispositionCounts,
            ),
            attemptConservation = HighContentionAttemptConservation(
                expectedAttemptsByIdentity = attemptsByIdentity.mapValues { (_, identityAttempts) ->
                    HighContentionIdentityAttemptExpectation(
                        attemptCount = identityAttempts.size,
                        winnerRule = HighContentionWinnerRule.EXACTLY_ONE,
                    )
                },
                attempts = attempts,
            ),
        ),
        failureInjection = HighContentionFailureInjectionEvidence(
            type = failure.kind,
            steps = failure.steps,
        ),
        invariantResults = invariantIds.map { invariantId ->
            HighContentionInvariantResult(
                invariantId = invariantId,
                authority = "postgresql",
                expectation = "$implementation live boundary preserves PostgreSQL authority",
                observation = "jobs=${delta.jobs},effects=${delta.effects},receipts=${delta.receipts},$boundaryEvidence",
                status = HighContentionInvariantStatus.PASS,
                evidenceReference = "evidence/$implementation-$profileId.json",
            )
        },
        observations = HighContentionReportObservations(
            realizedAuthorityKeyCardinality = delta.jobs.toInt(),
        ),
        deadlines = listOf(
            HighContentionDeadlineEvidence(
                phase = "profile",
                configuredBudgetNanos = TimeUnit.MILLISECONDS.toNanos(profileDeadlineMs),
                effectiveBudgetNanos = TimeUnit.MILLISECONDS.toNanos(profileDeadlineMs),
                absoluteDeadlineNanos = Math.addExact(
                    startedNanos,
                    TimeUnit.MILLISECONDS.toNanos(profileDeadlineMs),
                ),
                actualDurationNanos = actualDurationNanos,
                expired = false,
                timeoutOrigin = HighContentionTimeoutOrigin.NONE,
            ),
        ),
        result = HighContentionTerminalResult(
            terminalStatus = HighContentionTerminalStatus.PASS,
            correctness = HighContentionCorrectness.PASS,
            errorCode = HighContentionErrorCode.NONE,
            failedPhase = null,
            failedInvariantIds = emptyList(),
            redactedErrorClass = null,
        ),
        cleanup = HighContentionCleanupSummary(HighContentionCleanupResult.PASS),
        knownLimitations = knownLimitations,
    )
}
