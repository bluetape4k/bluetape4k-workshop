package io.bluetape4k.workshop.leader.tenantscheduler.domain

/**
 * scheduler report가 사용하는 제한된 outcome dimension이다.
 */
enum class TenantRunOutcome(
    val label: String,
) {
    EXECUTED("executed"),
    SKIPPED("skipped"),
    FAILED("failed"),
    STALE_HANDOFF("stale-handoff"),
}
