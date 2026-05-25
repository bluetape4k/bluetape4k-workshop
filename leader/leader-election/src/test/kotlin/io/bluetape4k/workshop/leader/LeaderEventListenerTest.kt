package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.ListeningLeaderElector
import io.bluetape4k.workshop.leader.service.LeaderEventListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
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

        val lockName = "test:event:elected:${UUID.randomUUID()}"
        repeat(3) {
            listeningElector.runIfLeader(lockName) { "work" }
        }

        handle.close()

        electedCount.get() shouldBeEqualTo 3
    }

    @Test
    fun `onSkipped callback is called when lock is held by another elector`() {
        val lockName = "test:event:skipped:${UUID.randomUUID()}"
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
    fun `events Flow emits Elected event when lock is acquired`() {
        val listeningElector = newListeningElector()
        val lockName = "test:event:flow:${UUID.randomUUID()}"
        val receivedEvents = Channel<LeaderElectionEvent>(Channel.BUFFERED)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        scope.launch {
            listeningElector.events.collect { event ->
                receivedEvents.trySend(event)
            }
        }

        // Allow the SharedFlow collector to subscribe before emitting.
        // SharedFlow has replay=0; events emitted before subscription are lost.
        Thread.sleep(100)

        listeningElector.runIfLeader(lockName) { "work" }

        // Allow the shared flow emission to propagate to the collector.
        val received = runBlocking {
            withTimeoutOrNull(2_000) { receivedEvents.receive() }
        }

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

        val lockName = "test:event:service:${UUID.randomUUID()}"
        repeat(3) {
            listeningElector.runIfLeader(lockName) { "work" }
        }

        // Small delay to allow the background Flow collector to process events.
        Thread.sleep(200)

        service.electedCount.get() shouldBeEqualTo 3
        service.skippedCount.get() shouldBeEqualTo 0

        service.close()
    }
}
