package io.bluetape4k.workshop.operations.jobconsole.highcontention

import com.fasterxml.jackson.annotation.JsonProperty

enum class HighContentionMode(
    val wireValue: String,
) {
    @JsonProperty("ci-correctness")
    CI_CORRECTNESS("ci-correctness"),

    @JsonProperty("local-reference")
    LOCAL_REFERENCE("local-reference"),
}

enum class ArrivalCurve(
    val wireValue: String,
) {
    @JsonProperty("burst")
    BURST("burst"),

    @JsonProperty("step")
    STEP("step"),

    @JsonProperty("retry-storm")
    RETRY_STORM("retry-storm"),
}

enum class FailureKind(
    val wireValue: String,
) {
    @JsonProperty("none")
    NONE("none"),

    @JsonProperty("duplicate-submission")
    DUPLICATE_SUBMISSION("duplicate-submission"),

    @JsonProperty("redis-path-outage")
    REDIS_PATH_OUTAGE("redis-path-outage"),

    @JsonProperty("redis-key-loss")
    REDIS_KEY_LOSS("redis-key-loss"),

    @JsonProperty("slow-provider")
    SLOW_PROVIDER("slow-provider"),

    @JsonProperty("worker-restart")
    WORKER_RESTART("worker-restart"),

    @JsonProperty("duplicate-delivery")
    DUPLICATE_DELIVERY("duplicate-delivery"),
}

data class HighContentionProfileContract(
    val contractSchemaVersion: Int,
    val profileSchemaVersion: Int,
    val allowedFields: List<String>,
    val modes: List<HighContentionMode>,
    val arrivalCurves: List<ArrivalCurve>,
    val failureKinds: List<FailureKind>,
    val limits: Map<String, HighContentionProfileLimits>,
)

data class HighContentionProfileLimits(
    val maxOperationCount: Int,
    val maxConcurrency: Int,
    val maxWarmupOperationCount: Int,
    val maxProfileDeadlineMs: Long,
)

data class HighContentionReportContract(
    val contractSchemaVersion: Int,
    val reportSchemaVersion: Int,
    val results: List<String>,
    val errorCodes: List<String>,
    val submissionDispositions: List<String>,
    val scheduleObservations: List<String>,
    val cleanupResults: List<String>,
    val requiredTopLevelFields: List<String>,
    val forbiddenEvidencePatterns: List<String>,
)

data class HighContentionChildDescriptorContract(
    val contractSchemaVersion: Int,
    val childDescriptorSchemaVersion: Int,
    val allowedFields: List<String>,
    val resourceLabelFields: List<String>,
    val requiredLabelKeys: List<String>,
    val forbiddenConfigurationFields: List<String>,
)

data class HighContentionSuiteManifest(
    val suiteSchemaVersion: Int,
    val profileSchemaVersion: Int,
    val reportSchemaVersion: Int,
    val childDescriptorSchemaVersion: Int,
    val runDeadlineMs: Long,
    val runCleanupActionBudgetsMs: RunCleanupActionBudgets,
    val runJournalFinalizeReserveMs: Long,
    val runCleanupReserveMs: Long,
    val dockerCleanupPollIntervalMs: Long,
    val dockerCleanupQuietPeriodMs: Long,
    val implementations: List<String>,
    val entries: List<HighContentionSuiteEntry>,
)

data class RunCleanupActionBudgets(
    val childProcesses: Long,
    val dockerDiscovery: Long,
    val artifactFinalization: Long,
)

data class HighContentionSuiteEntry(
    val mode: HighContentionMode,
    val profileId: String,
    val profileFile: String,
    val implementations: List<String>,
)

data class HighContentionProfile(
    val profileSchemaVersion: Int,
    val profileId: String,
    val mode: HighContentionMode,
    val seed: String,
    val arrivalCurve: ArrivalCurve,
    val operationCount: Int,
    val concurrency: Int,
    val dispatcherBacklogCapacity: Int,
    val maxScheduleDelayMs: Long,
    val warmupOperationCount: Int,
    val workloadDurationMs: Long,
    val epochs: List<HighContentionEpoch>,
    val retryShape: HighContentionRetryShape?,
    val contentionShape: HighContentionContentionShape,
    val expectedSubmissionOutcomes: ExpectedSubmissionOutcomes,
    val failure: HighContentionFailure,
    val operationTimeoutMs: Long,
    val injectionDeadlineMs: Long,
    val failureDetectionDeadlineMs: Long,
    val workloadJoinDeadlineMs: Long,
    val recoveryDeadlineMs: Long,
    val cleanupActionBudgetsMs: CleanupActionBudgets,
    val reportFinalizeReserveMs: Long,
    val cleanupReserveMs: Long,
    val profileDeadlineMs: Long,
    val expectedInvariants: ExpectedInvariants,
    val observationFields: List<String>,
    val knownLimitations: List<String>,
)

data class HighContentionEpoch(
    val durationMs: Long,
    val operationCount: Int,
)

data class HighContentionRetryShape(
    val identityCount: Int,
    val attemptsPerIdentity: Int,
    val epochDurationMs: Long,
)

data class HighContentionContentionShape(
    val authorityCount: Int,
    val hotAuthorityCount: Int,
    val identityCount: Int,
    val sameIdentityRatioPermille: Int,
)

data class ExpectedSubmissionOutcomes(
    val minimumDispatched: Int,
    val minimumCompleted: Int,
    val maximumLocalRejected: Int,
    val maximumMissedDeadline: Int,
)

data class HighContentionFailure(
    val kind: FailureKind,
    val triggerAcceptedCount: Int,
    val steps: List<String>,
)

data class CleanupActionBudgets(
    val application: Long,
    val clients: Long,
    val toxiproxy: Long,
    val redis: Long,
    val postgresql: Long,
    val network: Long,
) {
    fun total(): Long =
        listOf(application, clients, toxiproxy, redis, postgresql, network)
            .fold(0L, Math::addExact)
}

data class ExpectedInvariants(
    val job: List<String>,
    val ticket: List<String>,
)

data class ScheduleVectorDocument(
    val schemaVersion: Int,
    val algorithm: String,
    val vectors: List<ScheduleVector>,
    val vectorsSha256: String,
)

data class ScheduleVector(
    val name: String,
    val profileSchemaVersion: Int,
    val seed: String,
    val curve: ArrivalCurve,
    val operationCount: Int,
    val durationNanos: Long,
    val authorityWeights: List<Int>,
    val epochs: List<ScheduleEpoch>,
    val retryShape: ScheduleRetryShape?,
    val expectedTokens: List<ScheduleToken>,
)

data class ScheduleEpoch(
    val durationNanos: Long,
    val operationCount: Int,
)

data class ScheduleRetryShape(
    val identityCount: Int,
    val attemptsPerIdentity: Int,
)

data class ScheduleToken(
    val offsetNanos: Long,
    val stableOrdinal: Int,
    val identityOrdinal: Int,
    val attemptOrdinal: Int,
    val authorityOrdinal: Int,
)

data class HighContentionSelection(
    val profile: HighContentionProfile,
    val implementation: String,
)

data class LoadedHighContentionContract(
    val suite: HighContentionSuiteManifest,
    val profileContract: HighContentionProfileContract,
    val reportContract: HighContentionReportContract,
    val childDescriptorContract: HighContentionChildDescriptorContract,
    val scheduleVectors: ScheduleVectorDocument,
    val selections: List<HighContentionSelection>,
)
