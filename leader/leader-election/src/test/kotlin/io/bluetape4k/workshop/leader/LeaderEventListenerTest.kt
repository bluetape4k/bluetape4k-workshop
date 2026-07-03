package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.ListeningLeaderElector
import io.bluetape4k.workshop.leader.service.LeaderEventListenerService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [LeaderEventListenerService] and the [ListeningLeaderElector] event API.
 *
 * ## Key behaviours verified
 * - [LeaderElectionListener.onElected] is called when the lock is acquired.
 * - [LeaderElectionListener.onSkipped] is called when the lock is not acquired.
 * - [ListeningLeaderElector.events] Flow emits [LeaderElectionEvent.Elected] on acquisition.
 * - [LeaderEventListenerService] wires both the listener and the Flow collector correctly.
 */
class LeaderEventListenerTest : AbstractLeaderElectionTest() {

    @Test
    fun `onElected callback is called when lock is acquired`() {
        val listeningElector = newListeningElector()
        val electedCount = AtomicInteger(0)

        val handle = listeningElector.addListener(object : LeaderElectionListener {
            override fun onElected(lockName: String) {
                electedCount.incrementAndGet()
            }
        })

        val lockName = "test:event:elected:${Base58.randomString(8)}"
        repeat(3) {
            listeningElector.runIfLeader(lockName) { "work" }
        }

        handle.close()

        electedCount.get() shouldBeEqualTo 3
    }

    @Test
    fun `onSkipped callback is called when lock is held by another elector`() {
        val lockName = "test:event:skipped:${Base58.randomString(8)}"
        val followerElector = newListeningElector()
        val skippedCount = AtomicInteger(0)

        followerElector.addListener(object : LeaderElectionListener {
            override fun onSkipped(lockName: String) {
                skippedCount.incrementAndGet()
            }
        })

        // Leader holds the lock while follower tries to acquire and gets skipped.
        val leaderElector = newElector()
        leaderElector.runIfLeader(lockName) {
            followerElector.runIfLeader(lockName) { "should-skip" }
        }

        skippedCount.get() shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `events Flow emits Elected event when lock is acquired`() = runSuspendIO {
        val listeningElector = newListeningElector()
        val lockName = "test:event:flow:${Base58.randomString(8)}"
        val receivedEvents = Channel<LeaderElectionEvent>(Channel.BUFFERED)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            listeningElector.events.collect { event ->
                receivedEvents.trySend(event)
            }
        }

        listeningElector.runIfLeader(lockName) { "work" }

        // Allow the shared flow emission to propagate to the collector.
        val received = withTimeoutOrNull(2_000) { receivedEvents.receive() }

        received.shouldNotBeNull()
        (received is LeaderElectionEvent.Elected) shouldBeEqualTo true

        scope.cancel()
        receivedEvents.close()
    }

    @Test
    fun `LeaderEventListenerService counts events from ListeningLeaderElector`() {
        val listeningElector = newListeningElector()
        val service = LeaderEventListenerService(listeningElector)
        service.init()

        val lockName = "test:event:service:${Base58.randomString(8)}"
        repeat(3) {
            listeningElector.runIfLeader(lockName) { "work" }
        }

        await atMost Duration.ofSeconds(2) untilAsserted {
            service.electedCount.get() shouldBeEqualTo 3
            service.skippedCount.get() shouldBeEqualTo 0
        }

        service.close()
    }
}
