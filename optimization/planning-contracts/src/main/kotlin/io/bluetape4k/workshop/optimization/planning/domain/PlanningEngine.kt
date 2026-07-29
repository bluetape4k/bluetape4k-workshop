package io.bluetape4k.workshop.optimization.planning.domain

/**
 * Submits versioned planning datasets and reads normalized provider results.
 *
 * Implementations must preserve [PlanningSubmission.requestId] across retries
 * so the application-owned outbox remains the authoritative replay boundary.
 */
internal interface PlanningEngine {

    /** Provider served by this engine instance. */
    val provider: PlanningProvider

    /** Submits one planning request to the configured provider. */
    fun submit(request: PlanningSubmission): PlanningSubmissionResult

    /** Returns the latest normalized result, or `null` when it is unknown. */
    fun status(providerRequestId: ProviderRequestId): PlanningResult?
}
