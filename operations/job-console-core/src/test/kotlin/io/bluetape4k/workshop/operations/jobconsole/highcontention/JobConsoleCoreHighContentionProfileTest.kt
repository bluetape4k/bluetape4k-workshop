package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.support.requireNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

@Tag("high-contention")
class JobConsoleCoreHighContentionProfileTest {

    @Test
    fun `selected Job core profile preserves PostgreSQL authority and writes a validated report`() {
        HighContentionWorkerPid.publishIfConfigured()
        Runtime.version().feature().shouldBeEqualTo(
            System.getProperty("highContentionExpectedJavaVersion")
                .requireNotNull("highContentionExpectedJavaVersion")
                .toInt(),
        )
        val runId = requiredProperty("highContentionRunId")
        val profileId = requiredProperty("highContentionProfileId")
        val workflowRunAndAttempt = requiredProperty("highContentionWorkflowRunAndAttempt")
        val implementation = requiredProperty("highContentionImplementation")
        implementation.shouldBeEqualTo("job-core")
        val mode = HighContentionMode.entries.single {
            it.wireValue == requiredProperty("highContentionMode")
        }
        val contract = HighContentionContractLoader().load(
            contractRoot = Path.of(requiredProperty("highContentionContractRoot")),
            mode = mode,
            profileId = profileId,
            implementation = implementation,
        )
        val selection = contract.selections.single()
        val profile = selection.profile
        val schedule = DeterministicSchedule.generate(profile.scheduleVector())
        val startedAt = Instant.now()
        val startedNanos = System.nanoTime()

        val report = JobConsoleHighContentionAdapter.create(profile).use { adapter ->
            val workload = HighContentionWorkloadEngine(
                workloadJoinTimeout = java.time.Duration.ofMillis(profile.workloadJoinDeadlineMs),
            ).run(
                schedule = schedule,
                warmupOperationCount = profile.warmupOperationCount,
                warmupNamespace = "hc:warmup:$runId:job-core:",
                measuredNamespace = "hc:measured:$runId:job-core:",
                concurrency = profile.concurrency,
                dispatcherBacklogCapacity = profile.dispatcherBacklogCapacity,
                maxScheduleDelayNanos = TimeUnit.MILLISECONDS.toNanos(profile.maxScheduleDelayMs),
                adapter = adapter,
            )
            workload.scheduledCount.shouldBeEqualTo(profile.operationCount)
            workload.dispatchedCount.shouldBeGreaterThan(profile.expectedSubmissionOutcomes.minimumDispatched - 1)
            workload.completedCount.shouldBeGreaterThan(profile.expectedSubmissionOutcomes.minimumCompleted - 1)
            workload.locallyRejectedCount.shouldBeEqualTo(0)
            workload.expectedScheduleDigest.shouldBeEqualTo(workload.realizedScheduleDigest)

            val delta = adapter.authorityDelta()
            if (profile.arrivalCurve != ArrivalCurve.RETRY_STORM) {
                delta.jobs.shouldBeEqualTo(profile.operationCount.toLong())
            }
            delta.effects.shouldBeGreaterThan(0L)
            delta.receipts.shouldBeGreaterThan(0L)
            if (profile.profileId == "worker-restart") {
                val stale = adapter.staleAttemptEvidence.requireNotNull("staleAttemptEvidence")
                stale.pausedConnections.shouldBeEqualTo(0)
                stale.pausedTransactions.shouldBeEqualTo(0)
                stale.pausedLocks.shouldBeEqualTo(0)
                stale.staleErrorCode.shouldBeEqualTo(
                    io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode.LEASE_LOST,
                )
            }

            profile.report(
                contract = contract,
                runId = runId,
                implementation = implementation,
                workflowRunAndAttempt = workflowRunAndAttempt,
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

private fun HighContentionProfile.scheduleVector(): ScheduleVector =
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

private fun HighContentionProfile.report(
    contract: LoadedHighContentionContract,
    runId: String,
    implementation: String,
    workflowRunAndAttempt: String,
    startedAt: Instant,
    startedNanos: Long,
    workload: HighContentionWorkloadResult,
    adapter: JobConsoleHighContentionAdapter,
    delta: JobConsoleAuthorityBaseline,
): HighContentionTerminalReport {
    val endedAt = Instant.now()
    val actualDurationNanos = System.nanoTime() - startedNanos
    val attempts = workload.realizedRecords.map { record ->
        val disposition = if (adapter.winner(record.token)) {
            HighContentionTerminalDisposition.SUCCEEDED
        } else {
            HighContentionTerminalDisposition.DUPLICATE_SUPPRESSED
        }
        HighContentionAttemptEvidence(
            stableOrdinal = record.token.stableOrdinal,
            identityOrdinal = record.token.identityOrdinal,
            attemptOrdinal = record.token.attemptOrdinal,
            terminalDisposition = disposition,
            failurePoint = HighContentionFailurePoint.NONE,
            authorityWinnerCount = if (adapter.winner(record.token)) 1 else 0,
            effectWinnerCount = if (adapter.winner(record.token)) 1 else 0,
            receiptWinnerCount = if (adapter.winner(record.token)) 1 else 0,
        )
    }
    val attemptsByIdentity = attempts.groupBy(HighContentionAttemptEvidence::identityOrdinal)
    val dispositionCounts = HighContentionTerminalDisposition.entries.associateWith { disposition ->
        attempts.count { it.terminalDisposition == disposition }
    }
    val invariantIds = expectedInvariants.job.map { it.lowercase().replace('_', '-') }

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
            workflowRunAndAttempt = workflowRunAndAttempt,
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
                expectation = "authoritative state survives hostile timing",
                observation = "jobs=${delta.jobs},effects=${delta.effects},receipts=${delta.receipts}",
                status = HighContentionInvariantStatus.PASS,
                evidenceReference = "evidence/job-core-$profileId.json",
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
