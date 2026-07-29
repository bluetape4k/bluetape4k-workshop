package io.bluetape4k.workshop.leader.service

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.ListeningLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ListeningLeaderElector]가 내보내는 leader election event를 관찰하는 service입니다.
 *
 * 두 가지 상호 보완적인 event consumption pattern을 보여줍니다.
 * 1. **[LeaderElectionListener]** - [ListeningLeaderElector.addListener]로 추가하는 callback interface입니다.
 *    `runIfLeader`를 호출한 thread에서 동기 실행되며, metrics update나 state flag 같은 가벼운 side effect에 적합합니다.
 * 2. **[ListeningLeaderElector.events] Flow** - background coroutine에서 collect하는 cold-to-hot shared flow입니다.
 *    async fan-out, reactive pipeline, SSE stream에 적합합니다.
 *
 * ## 동작 / 계약
 * - [LeaderElectionListener]는 [init]에서 등록하고 [close]에서 제거합니다.
 * - Flow collection coroutine은 [init]에서 시작하고 [close]에서 cancel합니다.
 * - [electedCount], [revokedCount], [skippedCount]는 테스트를 위해 노출합니다.
 * - Flow collector의 error는 log로 남기고 elector로 전파하지 않습니다.
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
     * bean 생성 뒤 callback listener를 등록하고 Flow collector를 시작합니다.
     */
    @PostConstruct
    fun init() {
        // Pattern 1: LeaderElectionListener callback - 동기적이고 가볍습니다.
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

        // Pattern 2: Flow collection - async이며 fan-out이 가능합니다.
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "[EventFlow] Event flow collection stopped unexpectedly" }
            }
        }
    }

    /**
     * Spring context가 닫힐 때 listener를 제거하고 Flow collector를 cancel합니다.
     */
    @PreDestroy
    fun close() {
        closeQuietly("leader election listener") { listenerHandle?.close() }
        closeQuietly("leader event flow scope") { scope.cancel() }
    }

    private inline fun closeQuietly(resourceName: String, action: () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Failed to close $resourceName" }
        }
    }
}
