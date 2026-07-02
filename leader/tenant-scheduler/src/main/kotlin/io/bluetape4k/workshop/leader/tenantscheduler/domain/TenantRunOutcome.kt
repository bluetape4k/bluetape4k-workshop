package io.bluetape4k.workshop.leader.tenantscheduler.domain

/**
 * Bounded outcome dimension used by scheduler reports.
 */
enum class TenantRunOutcome(
    val label: String,
) {
    EXECUTED("executed"),
    SKIPPED("skipped"),
    FAILED("failed"),
    STALE_HANDOFF("stale-handoff"),
}
