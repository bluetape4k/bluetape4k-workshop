package io.bluetape4k.workshop.leader.service

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.ListeningLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service that observes leader election events emitted by [ListeningLeaderElector].
 *
 * Demonstrates two complementary event consumption patterns:
 * 1. **[LeaderElectionListener]** — callback interface added via [ListeningLeaderElector.addListener].
 *    Runs synchronously on the thread that calls `runIfLeader`; suitable for lightweight side effects
 *    such as metrics updates or state flags.
 * 2. **[ListeningLeaderElector.events] Flow** — cold-to-hot shared flow collected in a background
 *    coroutine. Suitable for async fan-out, reactive pipelines, or SSE streams.
 *
 * ## Behavior / Contract
 * - The [LeaderElectionListener] is registered in [init] and removed in [close].
 * - The Flow collection coroutine is started in [init] and cancelled in [close].
 * - [electedCount], [revokedCount], and [skippedCount] are exposed for testing.
 * - Errors in the Flow collector are logged and do not propagate to the elector.
 */
@Service
class LeaderEventListenerService(
    private val listeningLeaderElector: ListeningLeaderElector,
) {
    val electedCount = AtomicInteger(0)
    val revokedCount = AtomicInteger(0)
    val skippedCount = AtomicInteger(0)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var listenerHandle: AutoCloseable? = null

    companion object : KLogging()

    /**
     * Registers the callback listener and starts the Flow collector after bean construction.
     */
    @PostConstruct
    fun init() {
        // Pattern 1: LeaderElectionListener callback — synchronous, lightweight
        listenerHandle = listeningLeaderElector.addListener(object : LeaderElectionListener {
            override fun onElected(lockName: String) {
                electedCount.incrementAndGet()
                log.info { "[EventListener] ELECTED for '$lockName' — total elected=${electedCount.get()}" }
            }

            override fun onRevoked(lockName: String) {
                revokedCount.incrementAndGet()
                log.info { "[EventListener] REVOKED for '$lockName' — total revoked=${revokedCount.get()}" }
            }

            override fun onSkipped(lockName: String) {
                skippedCount.incrementAndGet()
                log.info { "[EventListener] SKIPPED '$lockName' — not elected this round" }
            }
        })

        // Pattern 2: Flow collection — async, fan-out capable
        scope.launch {
            try {
                listeningLeaderElector.events.collect { event ->
                    when (event) {
                        is LeaderElectionEvent.Elected ->
                            log.info { "[EventFlow] Elected for '${event.lockName}' leaderId=${event.leaderId}" }

                        is LeaderElectionEvent.Revoked ->
                            log.info { "[EventFlow] Revoked for '${event.lockName}'" }

                        is LeaderElectionEvent.Skipped ->
                            log.info { "[EventFlow] Skipped '${event.lockName}'" }
                    }
                }
            } catch (e: Exception) {
                log.warn(e) { "[EventFlow] Event flow collection stopped unexpectedly" }
            }
        }
    }

    /**
     * Removes the listener and cancels the Flow collector on Spring context close.
     */
    @PreDestroy
    fun close() {
        runCatching { listenerHandle?.close() }
        runCatching { scope.cancel() }
    }
}
