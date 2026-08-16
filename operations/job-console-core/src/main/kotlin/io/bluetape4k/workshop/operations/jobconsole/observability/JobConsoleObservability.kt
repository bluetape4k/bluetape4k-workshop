package io.bluetape4k.workshop.operations.jobconsole.observability

enum class DependencyState {
    UP,
    DEGRADED,
    DOWN,
}

data class JobConsoleReadiness(
    val ready: Boolean,
    val postgres: DependencyState,
    val redis: DependencyState,
    val policyFingerprint: String,
    val boundedWaitEnabled: Boolean,
    val reason: String? = null,
)

object JobConsoleObservability {
    private val allowedTagKeys = setOf("adapter", "operation", "state", "outcome")

    fun safeTags(tags: Map<String, String>): Map<String, String> = tags.filterKeys(allowedTagKeys::contains)
}
