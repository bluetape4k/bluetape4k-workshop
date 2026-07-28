package io.bluetape4k.workshop.commerce.voucherpool.fixture

import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** deterministic voucher-pool recovery demonstration의 닫힌 catalog입니다. */
internal enum class FixtureScenario(val slug: String) {
    REDIS_OUTAGE("redis-outage"),
    BLOOM_FALSE_POSITIVE("bloom-false-positive"),
    REVEAL_RESPONSE_LOSS("reveal-response-loss"),
    PAUSE_ALLOCATION_RACE("pause-allocation-race"),
    REDEEM_REVOKE_RACE("redeem-revoke-race"),
    WORKER_TAKEOVER("worker-takeover"),
    CIPHERTEXT_QUARANTINE("ciphertext-quarantine"),
    RESTORE_SMOKE("restore-smoke"),
}

/** process-local fixture signal 하나의 serializable snapshot입니다. */
internal data class FixtureState(
    val scenario: FixtureScenario,
    val armed: Boolean,
    val consumed: Boolean,
    val claimed: Boolean = false,
) : Serializable {
    fun armOnce(): FixtureState =
        if (armed || claimed || consumed) this else copy(armed = true)

    fun claimOnce(): FixtureState =
        if (!armed || claimed || consumed) this else copy(armed = false, claimed = true)

    fun finalizeClaim(committed: Boolean): FixtureState = when {
        !claimed -> this
        committed -> copy(claimed = false, consumed = true)
        else -> copy(armed = true, claimed = false, consumed = false)
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * deterministic failure-path demonstration을 위한 transaction-aware fixture signal입니다.
 *
 * configuration과 arming은 감싸는 database transaction이 commit된 뒤에만 적용됩니다.
 * 각 armed signal은 한 번만 소비할 수 있고 다시 configure될 때까지 consumed 상태로 남습니다.
 */
@Component
@Profile("test")
internal class VoucherPoolFixtures(
    private val jdbcExecutor: VoucherPoolJdbcExecutor,
) {
    private val states = ConcurrentHashMap<String, FixtureSlot>()
    private val claimTokens = AtomicLong()

    init {
        FixtureScenario.entries.forEach { scenario ->
            states[scenario.slug] = FixtureSlot(FixtureState(scenario, armed = false, consumed = false))
        }
    }

    fun catalog(): List<FixtureScenario> = FixtureScenario.entries

    fun state(name: String): FixtureState {
        val scenario = requireScenario(name)
        return checkNotNull(states[scenario.slug]) { "fixture state is missing" }.state
    }

    fun configureAfterCommit(name: String) {
        val scenario = requireScenario(name)
        jdbcExecutor.afterCommit {
            states.compute(scenario.slug) { _, current ->
                FixtureSlot(
                    state = FixtureState(scenario, armed = false, consumed = false),
                    generation = (current?.generation ?: 0L) + 1L,
                )
            }
        }
    }

    fun armAfterCommit(name: String) {
        val scenario = requireScenario(name)
        jdbcExecutor.afterCommit {
            states.compute(scenario.slug) { _, current ->
                val existing = current ?: FixtureSlot(FixtureState(scenario, armed = false, consumed = false))
                existing.copy(state = existing.state.armOnce())
            }
        }
    }

    fun claim(name: String): Boolean {
        val scenario = requireScenario(name)
        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "fixture claims require an active transaction synchronization"
        }
        val claimed = AtomicBoolean(false)
        val identity = AtomicReference<ClaimIdentity?>()
        val claimToken = claimTokens.incrementAndGet()
        states.compute(scenario.slug) { _, current ->
            val existing = current ?: FixtureSlot(FixtureState(scenario, armed = false, consumed = false))
            if (existing.state.armed && !existing.state.claimed && !existing.state.consumed) {
                claimed.set(true)
                identity.set(ClaimIdentity(existing.generation, claimToken))
                existing.copy(state = existing.state.claimOnce(), claimToken = claimToken)
            } else {
                existing
            }
        }
        if (claimed.get()) {
            val claimedIdentity = checkNotNull(identity.get())
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        val committed = status == TransactionSynchronization.STATUS_COMMITTED
                        states.computeIfPresent(scenario.slug) { _, current ->
                            if (current.matches(claimedIdentity)) {
                                current.copy(state = current.state.finalizeClaim(committed), claimToken = null)
                            } else {
                                current
                            }
                        }
                    }
                },
            )
        }
        return claimed.get()
    }

    private fun requireScenario(name: String): FixtureScenario =
        BY_SLUG[name] ?: throw IllegalArgumentException("unsupported fixture scenario")

    private companion object {
        val BY_SLUG = FixtureScenario.entries.associateBy(FixtureScenario::slug)
    }
}

private data class FixtureSlot(
    val state: FixtureState,
    val generation: Long = 0L,
    val claimToken: Long? = null,
) {
    fun matches(identity: ClaimIdentity): Boolean =
        generation == identity.generation && claimToken == identity.token
}

private data class ClaimIdentity(
    val generation: Long,
    val token: Long,
)
