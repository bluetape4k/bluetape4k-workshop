package io.bluetape4k.workshop.operations.jobconsole.highcontention

import com.fasterxml.jackson.annotation.JsonProperty
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.time.Instant

enum class HighContentionTerminalStatus {
    @JsonProperty("PASS")
    PASS,

    @JsonProperty("FAIL")
    FAIL,

    @JsonProperty("ERROR")
    ERROR,

    @JsonProperty("UNAVAILABLE")
    UNAVAILABLE,
}

enum class HighContentionCorrectness {
    @JsonProperty("PASS")
    PASS,

    @JsonProperty("FAIL")
    FAIL,

    @JsonProperty("NOT_EVALUATED")
    NOT_EVALUATED,
}

enum class HighContentionErrorCode {
    @JsonProperty("NONE")
    NONE,

    @JsonProperty("INVALID_PROFILE")
    INVALID_PROFILE,

    @JsonProperty("INVALID_REALIZATION")
    INVALID_REALIZATION,

    @JsonProperty("KEY_SCOPE_VIOLATION")
    KEY_SCOPE_VIOLATION,

    @JsonProperty("EXECUTION_ERROR")
    EXECUTION_ERROR,

    @JsonProperty("INJECTION_TIMEOUT")
    INJECTION_TIMEOUT,

    @JsonProperty("FAILURE_DETECTION_TIMEOUT")
    FAILURE_DETECTION_TIMEOUT,

    @JsonProperty("WORKLOAD_TIMEOUT")
    WORKLOAD_TIMEOUT,

    @JsonProperty("RECOVERY_TIMEOUT")
    RECOVERY_TIMEOUT,

    @JsonProperty("CLEANUP_TIMEOUT")
    CLEANUP_TIMEOUT,

    @JsonProperty("REPORT_SERIALIZATION")
    REPORT_SERIALIZATION,

    @JsonProperty("JOURNAL_ERROR")
    JOURNAL_ERROR,

    @JsonProperty("PARENT_CLEANUP_ERROR")
    PARENT_CLEANUP_ERROR,

    @JsonProperty("PREFLIGHT_UNAVAILABLE")
    PREFLIGHT_UNAVAILABLE,
}

data class HighContentionTerminalResult(
    val terminalStatus: HighContentionTerminalStatus,
    val correctness: HighContentionCorrectness,
    val errorCode: HighContentionErrorCode,
    val failedPhase: String?,
    val failedInvariantIds: List<String>,
    val redactedErrorClass: String?,
) {
    fun validate(): HighContentionTerminalResult =
        apply {
            failedPhase?.let { HighContentionArtifactPaths.requireIdentifier(it, "failedPhase") }
            failedInvariantIds.forEach {
                HighContentionArtifactPaths.requireIdentifier(it, "failedInvariantId")
            }
            failedInvariantIds
                .distinct()
                .size
                .requireEquals(failedInvariantIds.size, "failedInvariantIds cardinality")
            redactedErrorClass?.requireNotBlank("redactedErrorClass")
            when (terminalStatus) {
                HighContentionTerminalStatus.PASS -> {
                    correctness.requireEquals(HighContentionCorrectness.PASS, "PASS correctness")
                    errorCode.requireEquals(HighContentionErrorCode.NONE, "PASS errorCode")
                    failedPhase.requireEquals(null, "PASS failedPhase")
                    failedInvariantIds.requireEquals(emptyList(), "PASS failedInvariantIds")
                    redactedErrorClass.requireEquals(null, "PASS redactedErrorClass")
                }

                HighContentionTerminalStatus.FAIL -> {
                    correctness.requireEquals(HighContentionCorrectness.FAIL, "FAIL correctness")
                    errorCode.requireEquals(HighContentionErrorCode.NONE, "FAIL errorCode")
                    failedInvariantIds.requireNotEmpty("FAIL failedInvariantIds")
                    redactedErrorClass.requireEquals(null, "FAIL redactedErrorClass")
                }

                HighContentionTerminalStatus.ERROR -> {
                    if (errorCode == HighContentionErrorCode.NONE) {
                        throw IllegalArgumentException("ERROR requires a non-NONE errorCode")
                    }
                    failedPhase.requireNotBlank("ERROR failedPhase")
                }

                HighContentionTerminalStatus.UNAVAILABLE -> {
                    correctness.requireEquals(
                        HighContentionCorrectness.NOT_EVALUATED,
                        "UNAVAILABLE correctness",
                    )
                    errorCode.requireEquals(
                        HighContentionErrorCode.PREFLIGHT_UNAVAILABLE,
                        "UNAVAILABLE errorCode",
                    )
                    failedPhase.requireNotBlank("UNAVAILABLE failedPhase")
                    failedInvariantIds.requireEquals(emptyList(), "UNAVAILABLE failedInvariantIds")
                }
            }
        }
}

enum class HighContentionTerminalDisposition {
    SUCCEEDED,
    FAILED_CLOSED,
    EXECUTION_FAILED,
    LOCALLY_REJECTED,
    CANCELLED,
    TIMED_OUT,
    DUPLICATE_SUPPRESSED,
    IGNORED_FENCED,
}

enum class HighContentionFailurePoint {
    NONE,
    BEFORE_AUTHORITY,
    AFTER_AUTHORITY,
    UNKNOWN,
}

data class HighContentionSubmissionConservation(
    val scheduledCount: Int,
    val dispatchedCount: Int,
    val completedCount: Int,
    val cancelledCount: Int,
    val timedOutCount: Int,
    val locallyRejectedCount: Int,
    val terminalDispositionCounts: Map<HighContentionTerminalDisposition, Int>,
) {
    fun validate(): HighContentionSubmissionConservation =
        apply {
            scheduledCount.requireZeroOrPositiveNumber("scheduledCount")
            dispatchedCount.requireZeroOrPositiveNumber("dispatchedCount")
            completedCount.requireZeroOrPositiveNumber("completedCount")
            cancelledCount.requireZeroOrPositiveNumber("cancelledCount")
            timedOutCount.requireZeroOrPositiveNumber("timedOutCount")
            locallyRejectedCount.requireZeroOrPositiveNumber("locallyRejectedCount")
            terminalDispositionCounts.keys.requireEquals(
                HighContentionTerminalDisposition.entries.toSet(),
                "terminalDispositionCounts keys",
            )
            terminalDispositionCounts.values.forEach {
                it.requireZeroOrPositiveNumber("terminalDispositionCount")
            }
            scheduledCount.requireEquals(
                Math.addExact(dispatchedCount, locallyRejectedCount),
                "submission conservation",
            )
            dispatchedCount.requireEquals(
                Math.addExact(
                    completedCount,
                    Math.addExact(cancelledCount, timedOutCount),
                ),
                "terminal disposition conservation",
            )
            completedCount.requireEquals(
                completedDispositions.sumOf(terminalDispositionCounts::getValue),
                "completed terminal dispositions",
            )
            cancelledCount.requireEquals(
                terminalDispositionCounts.getValue(HighContentionTerminalDisposition.CANCELLED),
                "cancelled terminal disposition",
            )
            timedOutCount.requireEquals(
                terminalDispositionCounts.getValue(HighContentionTerminalDisposition.TIMED_OUT),
                "timed-out terminal disposition",
            )
            locallyRejectedCount.requireEquals(
                terminalDispositionCounts.getValue(HighContentionTerminalDisposition.LOCALLY_REJECTED),
                "locally-rejected terminal disposition",
            )
            dispatchedCount.requireEquals(
                HighContentionTerminalDisposition.entries
                    .filterNot { it == HighContentionTerminalDisposition.LOCALLY_REJECTED }
                    .sumOf(terminalDispositionCounts::getValue),
                "dispatched terminal dispositions",
            )
        }

    private companion object {
        val completedDispositions = setOf(
            HighContentionTerminalDisposition.SUCCEEDED,
            HighContentionTerminalDisposition.FAILED_CLOSED,
            HighContentionTerminalDisposition.EXECUTION_FAILED,
            HighContentionTerminalDisposition.DUPLICATE_SUPPRESSED,
            HighContentionTerminalDisposition.IGNORED_FENCED,
        )
    }
}

enum class HighContentionWinnerRule {
    EXACTLY_ONE,
    AT_MOST_ONE,
    ZERO,
}

data class HighContentionIdentityAttemptExpectation(
    val attemptCount: Int,
    val winnerRule: HighContentionWinnerRule,
)

data class HighContentionAttemptEvidence(
    val stableOrdinal: Int,
    val identityOrdinal: Int,
    val attemptOrdinal: Int,
    val terminalDisposition: HighContentionTerminalDisposition,
    val failurePoint: HighContentionFailurePoint,
    val authorityWinnerCount: Int,
    val effectWinnerCount: Int,
    val receiptWinnerCount: Int,
)

data class HighContentionAttemptConservation(
    val expectedAttemptsByIdentity: Map<Int, HighContentionIdentityAttemptExpectation>,
    val attempts: List<HighContentionAttemptEvidence>,
) {
    fun validate(): HighContentionAttemptConservation =
        apply {
            expectedAttemptsByIdentity.requireNotEmpty("expectedAttemptsByIdentity")
            expectedAttemptsByIdentity.forEach { (identityOrdinal, expectation) ->
                identityOrdinal.requireZeroOrPositiveNumber("expected identityOrdinal")
                expectation.attemptCount.requirePositiveNumber("expected attemptCount")
            }
            attempts.forEach { attempt ->
                attempt.stableOrdinal.requireZeroOrPositiveNumber("stableOrdinal")
                attempt.identityOrdinal.requireZeroOrPositiveNumber("identityOrdinal")
                attempt.attemptOrdinal.requireZeroOrPositiveNumber("attemptOrdinal")
                attempt.authorityWinnerCount.requireInRange(0, 1, "authorityWinnerCount")
                attempt.effectWinnerCount.requireInRange(0, 1, "effectWinnerCount")
                attempt.receiptWinnerCount.requireInRange(0, 1, "receiptWinnerCount")
                if (attempt.terminalDisposition == HighContentionTerminalDisposition.EXECUTION_FAILED) {
                    if (attempt.failurePoint == HighContentionFailurePoint.NONE) {
                        throw IllegalArgumentException("EXECUTION_FAILED requires a failurePoint")
                    }
                } else {
                    attempt.failurePoint.requireEquals(
                        HighContentionFailurePoint.NONE,
                        "non-execution-failed failurePoint",
                    )
                }
            }

            val expectedAttemptCount = expectedAttemptsByIdentity.values.fold(0) { total, expectation ->
                Math.addExact(total, expectation.attemptCount)
            }
            attempts.size.requireEquals(expectedAttemptCount, "attempt evidence count")
            attempts
                .map(HighContentionAttemptEvidence::stableOrdinal)
                .sorted()
                .requireEquals(attempts.indices.toList(), "attempt stable ordinals")

            val attemptsByIdentity = attempts.groupBy(HighContentionAttemptEvidence::identityOrdinal)
            attemptsByIdentity.keys.requireEquals(
                expectedAttemptsByIdentity.keys,
                "attempt identity ordinals",
            )
            expectedAttemptsByIdentity.forEach { (identityOrdinal, expectation) ->
                val identityAttempts = attemptsByIdentity.getValue(identityOrdinal)
                identityAttempts
                    .map(HighContentionAttemptEvidence::attemptOrdinal)
                    .sorted()
                    .requireEquals(
                        (0 until expectation.attemptCount).toList(),
                        "attempt ordinals for identity $identityOrdinal",
                    )
                validateWinnerCount(
                    identityAttempts.sumOf(HighContentionAttemptEvidence::authorityWinnerCount),
                    expectation.winnerRule,
                    "authority winner count for identity $identityOrdinal",
                )
                validateWinnerCount(
                    identityAttempts.sumOf(HighContentionAttemptEvidence::effectWinnerCount),
                    expectation.winnerRule,
                    "effect winner count for identity $identityOrdinal",
                )
                validateWinnerCount(
                    identityAttempts.sumOf(HighContentionAttemptEvidence::receiptWinnerCount),
                    expectation.winnerRule,
                    "receipt winner count for identity $identityOrdinal",
                )
            }
        }

    private fun validateWinnerCount(
        count: Int,
        rule: HighContentionWinnerRule,
        name: String,
    ) {
        when (rule) {
            HighContentionWinnerRule.EXACTLY_ONE -> count.requireEquals(1, name)
            HighContentionWinnerRule.AT_MOST_ONE -> count.requireInRange(0, 1, name)
            HighContentionWinnerRule.ZERO -> count.requireEquals(0, name)
        }
    }
}

data class HighContentionEffectiveConfiguration(
    val operationCount: Int,
    val concurrency: Int,
    val dispatcherBacklogCapacity: Int,
    val warmupOperationCount: Int,
) {
    fun validate(): HighContentionEffectiveConfiguration =
        apply {
            operationCount.requirePositiveNumber("operationCount")
            concurrency.requirePositiveNumber("concurrency")
            dispatcherBacklogCapacity.requireZeroOrPositiveNumber("dispatcherBacklogCapacity")
            warmupOperationCount.requireZeroOrPositiveNumber("warmupOperationCount")
        }
}

data class HighContentionEnvironmentEvidence(
    val sourceCommit: String,
    val sourceDirty: Boolean,
    val sourceReproducible: Boolean,
) {
    fun validate(): HighContentionEnvironmentEvidence =
        apply {
            sourceCommit.requireNotBlank("sourceCommit")
            if (sourceDirty && sourceReproducible) {
                throw IllegalArgumentException("dirty source cannot be reproducible")
            }
        }
}

data class HighContentionReportObservations(
    val realizedAuthorityKeyCardinality: Int = 0,
) {
    fun validate(): HighContentionReportObservations =
        apply {
            realizedAuthorityKeyCardinality.requireZeroOrPositiveNumber("realizedAuthorityKeyCardinality")
        }
}

data class HighContentionScheduleEvidence(
    val expectedTokenCount: Int,
    val expectedScheduleSha256: String,
    val realizedTokenManifestSha256: String,
) {
    fun validate(): HighContentionScheduleEvidence =
        apply {
            expectedTokenCount.requirePositiveNumber("expectedTokenCount")
            requireSha256(expectedScheduleSha256, "expectedScheduleSha256")
            requireSha256(realizedTokenManifestSha256, "realizedTokenManifestSha256")
        }
}

data class HighContentionResourceOutcome(
    val resourceKey: String,
    val state: HighContentionResourceState,
)

data class HighContentionWorkloadEvidence(
    val effectiveConfiguration: HighContentionEffectiveConfiguration,
    val schedule: HighContentionScheduleEvidence,
    val submissionConservation: HighContentionSubmissionConservation,
    val attemptConservation: HighContentionAttemptConservation,
) {
    fun validate(): HighContentionWorkloadEvidence =
        apply {
            effectiveConfiguration.validate()
            schedule.validate()
            submissionConservation.validate()
            attemptConservation.validate()
            effectiveConfiguration.operationCount.requireEquals(
                schedule.expectedTokenCount,
                "configured operationCount",
            )
            schedule.expectedTokenCount.requireEquals(
                submissionConservation.scheduledCount,
                "scheduled token count",
            )
            attemptConservation.attempts.size.requireEquals(
                schedule.expectedTokenCount,
                "realized attempt count",
            )
            val realizedDispositionCounts = HighContentionTerminalDisposition.entries.associateWith { disposition ->
                attemptConservation.attempts.count { it.terminalDisposition == disposition }
            }
            realizedDispositionCounts.requireEquals(
                submissionConservation.terminalDispositionCounts,
                "realized terminal disposition counts",
            )
        }
}

data class HighContentionFailureInjectionEvidence(
    val type: FailureKind,
    val steps: List<String>,
)

enum class HighContentionInvariantStatus {
    PASS,
    FAIL,
}

data class HighContentionInvariantResult(
    val invariantId: String,
    val authority: String,
    val expectation: String,
    val observation: String,
    val status: HighContentionInvariantStatus,
    val evidenceReference: String,
) {
    fun validate(): HighContentionInvariantResult =
        apply {
            HighContentionArtifactPaths.requireIdentifier(invariantId, "invariantId")
            authority.requireNotBlank("authority")
            expectation.requireNotBlank("expectation")
            observation.requireNotBlank("observation")
            HighContentionArtifactPaths.requireRelativeEvidence(evidenceReference)
        }
}

enum class HighContentionTimeoutOrigin {
    NONE,
    OPERATION,
    INJECTION,
    FAILURE_DETECTION,
    WORKLOAD_JOIN,
    RECOVERY,
    CLEANUP,
    PROFILE_EXECUTION,
    RUN_EXECUTION,
    RUN_CLEANUP,
}

data class HighContentionDeadlineEvidence(
    val phase: String,
    val configuredBudgetNanos: Long,
    val effectiveBudgetNanos: Long,
    val absoluteDeadlineNanos: Long,
    val actualDurationNanos: Long,
    val expired: Boolean,
    val timeoutOrigin: HighContentionTimeoutOrigin,
) {
    fun validate(): HighContentionDeadlineEvidence =
        apply {
            HighContentionArtifactPaths.requireIdentifier(phase, "deadline phase")
            configuredBudgetNanos.requirePositiveNumber("configuredBudgetNanos")
            effectiveBudgetNanos.requireZeroOrPositiveNumber("effectiveBudgetNanos")
            effectiveBudgetNanos.requireInRange(0, configuredBudgetNanos, "effectiveBudgetNanos")
            absoluteDeadlineNanos.requireZeroOrPositiveNumber("absoluteDeadlineNanos")
            actualDurationNanos.requireZeroOrPositiveNumber("actualDurationNanos")
            if (expired) {
                if (timeoutOrigin == HighContentionTimeoutOrigin.NONE) {
                    throw IllegalArgumentException("expired deadline must identify timeoutOrigin")
                }
            } else {
                timeoutOrigin.requireEquals(
                    HighContentionTimeoutOrigin.NONE,
                    "non-expired deadline timeoutOrigin",
                )
            }
        }
}

enum class HighContentionCleanupResult {
    PASS,
    FAIL,
}

data class HighContentionCleanupSummary(
    val result: HighContentionCleanupResult,
    val resourceOutcomes: List<HighContentionResourceOutcome> = emptyList(),
) {
    fun validate(): HighContentionCleanupSummary =
        apply {
            resourceOutcomes.forEach { outcome ->
                outcome.resourceKey.requireNotBlank("cleanup resourceKey")
                if (
                    outcome.state != HighContentionResourceState.CLOSED &&
                    outcome.state != HighContentionResourceState.CLOSE_FAILED
                ) {
                    throw IllegalArgumentException("cleanup resource state must be terminal")
                }
            }
            resourceOutcomes
                .map(HighContentionResourceOutcome::resourceKey)
                .distinct()
                .size
                .requireEquals(resourceOutcomes.size, "cleanup resource key cardinality")
            val expectedResult = if (
                resourceOutcomes.any { it.state == HighContentionResourceState.CLOSE_FAILED }
            ) {
                HighContentionCleanupResult.FAIL
            } else {
                HighContentionCleanupResult.PASS
            }
            result.requireEquals(expectedResult, "cleanup result")
        }
}

data class HighContentionTerminalReport(
    val reportSchemaVersion: Int,
    val suiteSchemaVersion: Int,
    val profileSchemaVersion: Int,
    val runId: String,
    val profileId: String,
    val mode: HighContentionMode,
    val implementation: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val environment: HighContentionEnvironmentEvidence,
    val phaseDurationsNanos: Map<String, Long>,
    val workload: HighContentionWorkloadEvidence?,
    val failureInjection: HighContentionFailureInjectionEvidence,
    val invariantResults: List<HighContentionInvariantResult>,
    val observations: HighContentionReportObservations,
    val deadlines: List<HighContentionDeadlineEvidence>,
    val observationScope: String = "developer-or-ci-reference",
    val crossImplementationComparable: Boolean = false,
    val productionCapacityClaim: Boolean = false,
    val result: HighContentionTerminalResult,
    val cleanup: HighContentionCleanupSummary,
    val knownLimitations: List<String>,
) {
    fun validate(): HighContentionTerminalReport =
        apply {
            reportSchemaVersion.requireEquals(1, "reportSchemaVersion")
            suiteSchemaVersion.requireEquals(1, "suiteSchemaVersion")
            profileSchemaVersion.requireEquals(1, "profileSchemaVersion")
            HighContentionArtifactPaths.requireIdentifier(runId, "runId")
            HighContentionArtifactPaths.requireIdentifier(profileId, "profileId")
            HighContentionArtifactPaths.requireIdentifier(implementation, "implementation")
            if (endedAt.isBefore(startedAt)) {
                throw IllegalArgumentException("endedAt must not precede startedAt")
            }
            environment.validate()
            phaseDurationsNanos.requireNotEmpty("phaseDurationsNanos")
            phaseDurationsNanos.forEach { (phase, duration) ->
                HighContentionArtifactPaths.requireIdentifier(phase, "phase")
                duration.requireZeroOrPositiveNumber("phase duration")
            }
            failureInjection.steps.forEach { it.requireNotBlank("failure injection step") }
            observations.validate()
            deadlines.requireNotEmpty("deadlines")
            deadlines.forEach(HighContentionDeadlineEvidence::validate)
            observationScope.requireEquals("developer-or-ci-reference", "observationScope")
            crossImplementationComparable.requireEquals(false, "crossImplementationComparable")
            productionCapacityClaim.requireEquals(false, "productionCapacityClaim")
            result.validate()
            val evaluated = result.correctness != HighContentionCorrectness.NOT_EVALUATED
            if (evaluated) {
                workload.requireNotNull("evaluated workload").validate()
                invariantResults.requireNotEmpty("evaluated invariantResults")
                invariantResults.forEach(HighContentionInvariantResult::validate)
            } else {
                workload.requireEquals(null, "not-evaluated workload")
                invariantResults.requireEquals(emptyList(), "not-evaluated invariantResults")
            }
            cleanup.validate()
            if (cleanup.result == HighContentionCleanupResult.FAIL) {
                result.terminalStatus.requireEquals(
                    HighContentionTerminalStatus.ERROR,
                    "cleanup failure result",
                )
                result.errorCode.requireEquals(
                    HighContentionErrorCode.CLEANUP_TIMEOUT,
                    "cleanup failure errorCode",
                )
            }
            if (result.errorCode == HighContentionErrorCode.CLEANUP_TIMEOUT) {
                cleanup.result.requireEquals(HighContentionCleanupResult.FAIL, "cleanup failure evidence")
            }
            val executionFailureCount = workload
                ?.attemptConservation
                ?.attempts
                ?.count { it.terminalDisposition == HighContentionTerminalDisposition.EXECUTION_FAILED }
                ?: 0
            if (executionFailureCount > 0) {
                result.terminalStatus.requireEquals(
                    HighContentionTerminalStatus.ERROR,
                    "execution failure terminalStatus",
                )
                result.errorCode.requireEquals(
                    HighContentionErrorCode.EXECUTION_ERROR,
                    "execution failure errorCode",
                )
            }
            if (result.errorCode == HighContentionErrorCode.EXECUTION_ERROR) {
                executionFailureCount.requirePositiveNumber("execution failure evidence")
            }
            val failedInvariantIds = invariantResults
                .filter { it.status == HighContentionInvariantStatus.FAIL }
                .map(HighContentionInvariantResult::invariantId)
                .sorted()
            result.failedInvariantIds
                .sorted()
                .requireEquals(failedInvariantIds, "failed invariant evidence")
            if (evaluated) {
                val derivedCorrectness = if (failedInvariantIds.isNotEmpty()) {
                    HighContentionCorrectness.FAIL
                } else {
                    HighContentionCorrectness.PASS
                }
                result.correctness.requireEquals(derivedCorrectness, "derived correctness")
            }
            knownLimitations.requireNotEmpty("knownLimitations")
        }
}

private fun requireSha256(
    value: String,
    name: String,
): String {
    val valid = value.requireNotBlank(name)
    if (!SHA256_REGEX.matches(valid)) {
        throw IllegalArgumentException("$name must be a lower-case SHA-256 digest")
    }
    return valid
}

private val SHA256_REGEX = Regex("[0-9a-f]{64}")
