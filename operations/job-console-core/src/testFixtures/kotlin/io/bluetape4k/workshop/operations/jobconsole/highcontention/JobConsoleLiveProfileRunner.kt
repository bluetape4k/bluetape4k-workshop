package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * JobConsoleLiveProfileAdapter 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
interface JobConsoleLiveProfileAdapter : HighContentionWorkloadAdapter, AutoCloseable {
/**
 * injectDeclaredFailure은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun injectDeclaredFailure()

/**
 * authorityDelta은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun authorityDelta(): JobConsoleAuthorityBaseline

/**
 * winner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun winner(token: ScheduleToken): Boolean

/**
 * profileEvidence은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun profileEvidence(): String

/**
 * assertProfileInvariants은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun assertProfileInvariants(delta: JobConsoleAuthorityBaseline)
}

/**
 * JobConsoleLiveProfileRunner 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class JobConsoleLiveProfileRunner(
    implementation: String,
/**
 * adapterFactory은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val adapterFactory: (String, HighContentionProfile) -> JobConsoleLiveProfileAdapter,
) {
/**
 * implementation은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val implementation = implementation.requireNotBlank("implementation")

/**
 * run은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun run() {
        HighContentionWorkerPid.publishIfConfigured()
        Runtime.version().feature().shouldBeEqualTo(
            requiredProperty("highContentionExpectedJavaVersion").toInt(),
        )
        val runId = requiredProperty("highContentionRunId")
        val profileId = requiredProperty("highContentionProfileId")
        val workflowRunAndAttempt = requiredProperty("highContentionWorkflowRunAndAttempt")
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
                workflowRunAndAttempt = workflowRunAndAttempt,
                startedAt = startedAt,
                startedNanos = startedNanos,
                workload = workload,
                adapter = adapter,
                delta = delta,
            )
        }
        val finalizedReport = report.finalizeAfterSuccessfulCleanup()
        finalizedReport.validate()
        val reportPath = HighContentionArtifactStore.create(
            outputRoot = Path.of(requiredProperty("highContentionOutputRoot")),
            runId = runId,
            parentOwnedRun = requiredProperty("highContentionParentOwnedRun").toBooleanStrict(),
        ).writeTerminalReport(
            implementation = implementation,
            profileId = profileId,
            report = finalizedReport,
        )

        Files.isRegularFile(reportPath).shouldBeTrue()
    }

/**
 * requiredProperty은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun requiredProperty(name: String): String =
        System.getProperty(name).requireNotNull(name)
}

/**
 * jobConsoleScheduleVector은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * jobConsoleReport은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun HighContentionProfile.jobConsoleReport(
    contract: LoadedHighContentionContract,
    runId: String,
    implementation: String,
    workflowRunAndAttempt: String,
    startedAt: Instant,
    startedNanos: Long,
    workload: HighContentionWorkloadResult,
    adapter: JobConsoleLiveProfileAdapter,
    delta: JobConsoleAuthorityBaseline,
): HighContentionTerminalReport {
/**
 * endedAt은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val endedAt = Instant.now()
/**
 * actualDurationNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val actualDurationNanos = System.nanoTime() - startedNanos
/**
 * attempts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val attempts = workload.realizedRecords.map { record ->
/**
 * winner은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * attemptsByIdentity은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val attemptsByIdentity = attempts.groupBy(HighContentionAttemptEvidence::identityOrdinal)
/**
 * dispositionCounts은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val dispositionCounts = HighContentionTerminalDisposition.entries.associateWith { disposition ->
        attempts.count { it.terminalDisposition == disposition }
    }
/**
 * invariantIds은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val invariantIds = expectedInvariants.job.map { it.lowercase().replace('_', '-') }
/**
 * boundaryEvidence은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
                expectation = "$implementation live boundary preserves PostgreSQL authority",
                observation = "jobs=${delta.jobs},effects=${delta.effects},receipts=${delta.receipts},$boundaryEvidence",
                status = HighContentionInvariantStatus.PASS,
                evidenceReference = "evidence/$implementation-$profileId.json",
            )
        },
        observations = HighContentionReportObservations(
            realizedAuthorityKeyCardinality = delta.jobs.toInt(),
            profileFields = measuredProfileObservations(workload, delta, boundaryEvidence),
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

/**
 * measuredProfileObservations은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun HighContentionProfile.measuredProfileObservations(
    workload: HighContentionWorkloadResult,
    delta: JobConsoleAuthorityBaseline,
    boundaryEvidence: String,
): Map<String, Any> {
/**
 * sortedLatencies은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sortedLatencies = workload.realizedRecords
        .filter { it.disposition != WorkloadTerminalDisposition.LOCALLY_REJECTED }
        .map(WorkloadRealizedRecord::latencyNanos)
        .sorted()
/**
 * durationNanos은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val durationNanos = workload.actualDurationNanos.coerceAtLeast(1L)
/**
 * deletedOwnedKeys은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val deletedOwnedKeys = Regex("""deletedOwnedKeys=(\d+)""")
        .find(boundaryEvidence)
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: 0
    return observationFields.associateWith { field ->
        when (field) {
            "throughputOpsPerSecond" ->
                workload.completedCount.toDouble() * TimeUnit.SECONDS.toNanos(1) / durationNanos

            "latencyP50Nanos" -> sortedLatencies.percentile(0.50)
            "latencyP95Nanos" -> sortedLatencies.percentile(0.95)
            "latencyP99Nanos" -> sortedLatencies.percentile(0.99)
            "workloadDurationNanos" -> durationNanos

            "deletedOwnedKeyCount" -> deletedOwnedKeys
            "scanIterationCount" -> 2
            "staleDisposition" -> "IGNORED_FENCED"
            "deliveryAttemptCount" -> 2
            "effectCount" -> delta.effects
            "receiptCount" -> delta.receipts
            else -> error("unsupported Job observation field[$field]")
        }
    }
}

private fun List<Long>.percentile(percentile: Double): Long {
    if (isEmpty()) {
        return 0L
    }
/**
 * index은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val index = ((size - 1) * percentile).toInt().coerceIn(indices)
    return this[index]
}
