package io.bluetape4k.workshop.commerce.ticket.highcontention

import com.fasterxml.jackson.annotation.JsonProperty

internal enum class TicketHighContentionMode(
    val wireValue: String,
) {
    @JsonProperty("ci-correctness")
    CI_CORRECTNESS("ci-correctness"),

    @JsonProperty("local-reference")
    LOCAL_REFERENCE("local-reference"),
}

internal enum class TicketArrivalCurve {
    @JsonProperty("burst")
    BURST,

    @JsonProperty("step")
    STEP,

    @JsonProperty("retry-storm")
    RETRY_STORM,
}

internal enum class TicketFailureKind {
    @JsonProperty("none")
    NONE,

    @JsonProperty("duplicate-submission")
    DUPLICATE_SUBMISSION,

    @JsonProperty("redis-path-outage")
    REDIS_PATH_OUTAGE,

    @JsonProperty("redis-key-loss")
    REDIS_KEY_LOSS,

    @JsonProperty("slow-provider")
    SLOW_PROVIDER,

    @JsonProperty("worker-restart")
    WORKER_RESTART,

    @JsonProperty("duplicate-delivery")
    DUPLICATE_DELIVERY,
}

internal data class TicketProfileContract(
    val contractSchemaVersion: Int,
    val profileSchemaVersion: Int,
    val allowedFields: List<String>,
    val modes: List<TicketHighContentionMode>,
    val arrivalCurves: List<TicketArrivalCurve>,
    val failureKinds: List<TicketFailureKind>,
    val limits: Map<String, TicketProfileLimits>,
)

internal data class TicketProfileLimits(
    val maxOperationCount: Int,
    val maxConcurrency: Int,
    val maxWarmupOperationCount: Int,
    val maxProfileDeadlineMs: Long,
)

internal data class TicketReportContract(
    val contractSchemaVersion: Int,
    val reportSchemaVersion: Int,
    val results: List<String>,
    val errorCodes: List<String>,
    val submissionDispositions: List<String>,
    val failurePoints: List<String>,
    val scheduleObservations: List<String>,
    val cleanupResults: List<String>,
    val requiredTopLevelFields: List<String>,
    val forbiddenEvidencePatterns: List<String>,
)

internal data class TicketChildDescriptorContract(
    val contractSchemaVersion: Int,
    val childDescriptorSchemaVersion: Int,
    val allowedFields: List<String>,
    val resourceLabelFields: List<String>,
    val requiredLabelKeys: List<String>,
    val forbiddenConfigurationFields: List<String>,
)

internal data class TicketHighContentionSuite(
    val suiteSchemaVersion: Int,
    val profileSchemaVersion: Int,
    val reportSchemaVersion: Int,
    val childDescriptorSchemaVersion: Int,
    val runDeadlineMs: Long,
    val runCleanupActionBudgetsMs: TicketRunCleanupBudgets,
    val runJournalFinalizeReserveMs: Long,
    val runCleanupReserveMs: Long,
    val dockerCleanupPollIntervalMs: Long,
    val dockerCleanupQuietPeriodMs: Long,
    val implementations: List<String>,
    val entries: List<TicketHighContentionSuiteEntry>,
)

internal data class TicketRunCleanupBudgets(
    val childProcesses: Long,
    val dockerDiscovery: Long,
    val artifactFinalization: Long,
)

internal data class TicketHighContentionSuiteEntry(
    val mode: TicketHighContentionMode,
    val profileId: String,
    val profileFile: String,
    val implementations: List<String>,
)

internal data class TicketHighContentionProfile(
    val profileSchemaVersion: Int,
    val profileId: String,
    val mode: TicketHighContentionMode,
    val seed: String,
    val arrivalCurve: TicketArrivalCurve,
    val operationCount: Int,
    val concurrency: Int,
    val dispatcherBacklogCapacity: Int,
    val maxScheduleDelayMs: Long,
    val warmupOperationCount: Int,
    val workloadDurationMs: Long,
    val epochs: List<TicketHighContentionEpoch>,
    val retryShape: TicketRetryShape?,
    val contentionShape: TicketContentionShape,
    val expectedSubmissionOutcomes: TicketExpectedSubmissionOutcomes,
    val failure: TicketHighContentionFailure,
    val operationTimeoutMs: Long,
    val injectionDeadlineMs: Long,
    val failureDetectionDeadlineMs: Long,
    val workloadJoinDeadlineMs: Long,
    val recoveryDeadlineMs: Long,
    val cleanupActionBudgetsMs: TicketCleanupBudgets,
    val reportFinalizeReserveMs: Long,
    val cleanupReserveMs: Long,
    val profileDeadlineMs: Long,
    val expectedInvariants: TicketExpectedInvariants,
    val observationFields: List<String>,
    val knownLimitations: List<String>,
)

internal data class TicketHighContentionEpoch(
    val durationMs: Long,
    val operationCount: Int,
)

internal data class TicketRetryShape(
    val identityCount: Int,
    val attemptsPerIdentity: Int,
    val epochDurationMs: Long,
)

internal data class TicketContentionShape(
    val authorityCount: Int,
    val hotAuthorityCount: Int,
    val identityCount: Int,
    val sameIdentityRatioPermille: Int,
)

internal data class TicketExpectedSubmissionOutcomes(
    val minimumDispatched: Int,
    val minimumCompleted: Int,
    val maximumLocalRejected: Int,
    val maximumMissedDeadline: Int,
)

internal data class TicketHighContentionFailure(
    val kind: TicketFailureKind,
    val triggerAcceptedCount: Int,
    val steps: List<String>,
)

internal data class TicketCleanupBudgets(
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

internal data class TicketExpectedInvariants(
    val job: List<String>,
    val ticket: List<String>,
)

internal data class TicketScheduleVectorDocument(
    val schemaVersion: Int,
    val algorithm: String,
    val vectors: List<TicketScheduleVector>,
    val vectorsSha256: String,
)

internal data class TicketScheduleVector(
    val name: String,
    val profileSchemaVersion: Int,
    val seed: String,
    val curve: TicketArrivalCurve,
    val operationCount: Int,
    val durationNanos: Long,
    val authorityWeights: List<Int>,
    val epochs: List<TicketScheduleEpoch>,
    val retryShape: TicketScheduleRetryShape?,
    val expectedTokens: List<TicketScheduleToken>,
)

internal data class TicketScheduleEpoch(
    val durationNanos: Long,
    val operationCount: Int,
)

internal data class TicketScheduleRetryShape(
    val identityCount: Int,
    val attemptsPerIdentity: Int,
)

internal data class TicketScheduleToken(
    val offsetNanos: Long,
    val stableOrdinal: Int,
    val identityOrdinal: Int,
    val attemptOrdinal: Int,
    val authorityOrdinal: Int,
)

internal data class TicketHighContentionSelection(
    val profile: TicketHighContentionProfile,
    val implementation: String,
)

internal data class LoadedTicketHighContentionContract(
    val suite: TicketHighContentionSuite,
    val scheduleVectors: TicketScheduleVectorDocument,
    val requiredReportFields: Set<String>,
    val forbiddenEvidencePatterns: List<String>,
    val selections: List<TicketHighContentionSelection>,
)
