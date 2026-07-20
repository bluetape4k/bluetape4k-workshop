package io.bluetape4k.workshop.commerce.voucher.fixture

import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal data class VoucherScenarioExpectation(
    val campaignState: String,
    val claimState: String?,
    val reviewOpen: Boolean,
    val durableAuthority: String = "POSTGRESQL",
    val auditReason: String,
    val sseEvent: String,
)

internal data class VoucherScenarioDefinition(
    val slug: String,
    val summary: String,
    val expected: VoucherScenarioExpectation,
    val riskSignal: RiskSignal? = null,
    val riskOperation: FixtureRiskOperation? = null,
)

internal enum class FixtureRiskOperation {
    ALLOCATION,
    REDEMPTION,
}

/** Closed, deterministic cookbook shared by the guarded fixture API and browser console. */
@Component
@Profile("local", "demo", "test")
internal class VoucherScenarioFixtures {
    private val configured = ConcurrentHashMap<FixtureSubject, VoucherScenarioDefinition>()

    fun catalog(): List<VoucherScenarioDefinition> = DEFINITIONS

    fun definition(slug: String): VoucherScenarioDefinition =
        BY_SLUG[slug] ?: throw IllegalArgumentException("unsupported fixture scenario")

    fun configure(
        slug: String,
        tenantId: String,
        principalRef: String,
    ): VoucherScenarioDefinition {
        val definition = definition(slug)
        if (definition.riskOperation != null) {
            afterCommit {
                configured[FixtureSubject(tenantId, principalRef)] = definition
            }
        }
        return definition
    }

    fun consumeRiskSignal(
        tenantId: String,
        principalRef: String,
        operation: FixtureRiskOperation,
    ): RiskSignal? {
        val subject = FixtureSubject(tenantId, principalRef)
        val definition = configured[subject] ?: return null
        if (definition.riskOperation != operation) return null
        configured.remove(subject, definition)
        return definition.riskSignal
    }

    fun reset(tenantId: String): Int {
        val signals = configured.filterKeys { it.tenantId == tenantId }
        afterCommit {
            signals.forEach { (subject, definition) -> configured.remove(subject, definition) }
        }
        return signals.size
    }

    /** Keeps process-local demo signals consistent with the enclosing PostgreSQL transaction. */
    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private data class FixtureSubject(val tenantId: String, val principalRef: String)

    companion object {
        const val DEMO_TENANT = "voucher-demo"
        val FIXED_TIME: Instant = Instant.parse("2026-07-20T00:00:00Z")

        private val DEFINITIONS =
            listOf(
                scenario("happy-allocation", "Allocation and redemption succeed", "ACTIVE", "REDEEMED", false, "VOUCHER_REDEEMED"),
                scenario("same-key-response-loss", "A lost response replays exactly", "ACTIVE", "ALLOCATED", false, "IDEMPOTENT_REPLAY"),
                scenario("capacity-race", "Concurrent allocation stops at capacity", "ACTIVE", "ALLOCATED", false, "CAPACITY_EXHAUSTED"),
                scenario("allocation-review", "Allocation enters operator review", "ACTIVE", "PENDING_REVIEW", true, "ALLOCATION_REVIEW_OPENED", RiskSignal.REVIEW, FixtureRiskOperation.ALLOCATION),
                scenario("redemption-review", "Redemption enters operator review", "ACTIVE", "PENDING_REVIEW", true, "REDEMPTION_REVIEW_OPENED", RiskSignal.REVIEW, FixtureRiskOperation.REDEMPTION),
                scenario("pause-allocation-race", "Pause and allocation record one policy winner", "PAUSED", "ALLOCATED", false, "CAMPAIGN_PAUSED"),
                scenario("redeem-revoke-race", "Redeem and revoke record one terminal winner", "ACTIVE", "REDEEMED", false, "CLAIM_TERMINAL_RACE"),
                scenario("policy-change", "A stale policy revision fails closed", "ACTIVE", "ALLOCATED", false, "STALE_POLICY_VERSION"),
                scenario("redis-outage", "Redis loss falls back to PostgreSQL", "ACTIVE", "ALLOCATED", false, "REDIS_DEGRADED", RiskSignal.UNKNOWN, FixtureRiskOperation.ALLOCATION),
                scenario("bloom-false-positive", "Bloom positive opens review without terminal rejection", "ACTIVE", "PENDING_REVIEW", true, "BLOOM_REVIEW_OPENED", RiskSignal.REVIEW, FixtureRiskOperation.ALLOCATION),
                scenario("delayed-duplicate-out-of-order", "Delayed duplicates apply once and stale order conflicts", "ACTIVE", null, false, "DELAYED_EVENT_CONFLICT"),
            )
        private val BY_SLUG = DEFINITIONS.associateBy(VoucherScenarioDefinition::slug)

        private fun scenario(
            slug: String,
            summary: String,
            campaignState: String,
            claimState: String?,
            reviewOpen: Boolean,
            auditReason: String,
            riskSignal: RiskSignal? = null,
            riskOperation: FixtureRiskOperation? = null,
        ): VoucherScenarioDefinition =
            VoucherScenarioDefinition(
                slug = slug,
                summary = summary,
                expected =
                    VoucherScenarioExpectation(
                        campaignState = campaignState,
                        claimState = claimState,
                        reviewOpen = reviewOpen,
                        auditReason = auditReason,
                        sseEvent = "audit",
                    ),
                riskSignal = riskSignal,
                riskOperation = riskOperation,
            )
    }
}
