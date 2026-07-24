package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.operations.jobconsole.highcontention.ArrivalCurve
import io.bluetape4k.workshop.operations.jobconsole.highcontention.DeterministicSchedule
import io.bluetape4k.workshop.operations.jobconsole.highcontention.FailureKind
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionArtifactStore
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionAttemptConservation
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionAttemptEvidence
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionCleanupResult
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionCleanupSummary
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionContractLoader
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionCorrectness
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionDeadlineEvidence
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionEffectiveConfiguration
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionEnvironmentEvidence
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionErrorCode
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionFailureInjectionEvidence
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionFailurePoint
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionIdentityAttemptExpectation
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionInvariantResult
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionInvariantStatus
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionMode
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionProfile
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionReportObservations
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionScheduleEvidence
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionSubmissionConservation
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionTerminalDisposition
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionTerminalReport
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionTerminalResult
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionTerminalStatus
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionTimeoutOrigin
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionWinnerRule
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionWorkloadEvidence
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionWorkloadEngine
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionWorkloadResult
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleAuthorityBaseline
import io.bluetape4k.workshop.operations.jobconsole.highcontention.LoadedHighContentionContract
import io.bluetape4k.workshop.operations.jobconsole.highcontention.ScheduleEpoch
import io.bluetape4k.workshop.operations.jobconsole.highcontention.ScheduleRetryShape
import io.bluetape4k.workshop.operations.jobconsole.highcontention.ScheduleVector
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

@Tag("integration")
@Tag("high-contention")
class SpringJobConsoleHighContentionProfileTest {

    @Test
    fun `selected Spring profile crosses HTTP Redis and application lifecycle boundaries`() {
        Runtime.version().feature().shouldBeEqualTo(
            System.getProperty("highContentionExpectedJavaVersion")
                .requireNotNull("highContentionExpectedJavaVersion")
                .toInt(),
        )
        val runId = requiredProperty("highContentionRunId")
        val profileId = requiredProperty("highContentionProfileId")
        val implementation = requiredProperty("highContentionImplementation")
        implementation.shouldBeEqualTo("job-spring")
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
        SpringJobConsoleProfileAction.resolve(profile.profileId).profileId.shouldBeEqualTo(profileId)
        val schedule = DeterministicSchedule.generate(profile.springScheduleVector())
        val startedAt = Instant.now()
        val startedNanos = System.nanoTime()

        val report = SpringJobConsoleLiveAdapter.create(runId, profile).use { adapter ->
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
                warmupNamespace = "hc:warmup:$runId:job-spring:",
                measuredNamespace = "hc:measured:$runId:job-spring:",
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

            profile.springReport(
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

private fun HighContentionProfile.springScheduleVector(): ScheduleVector =
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

private fun HighContentionProfile.springReport(
    contract: LoadedHighContentionContract,
    runId: String,
    implementation: String,
    startedAt: Instant,
    startedNanos: Long,
    workload: HighContentionWorkloadResult,
    adapter: SpringJobConsoleLiveAdapter,
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
    val springEvidence = adapter.springEvidence()

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
                expectation = "Spring live boundary preserves PostgreSQL authority",
                observation = "jobs=${delta.jobs},effects=${delta.effects},receipts=${delta.receipts},$springEvidence",
                status = HighContentionInvariantStatus.PASS,
                evidenceReference = "evidence/job-spring-$profileId.json",
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
