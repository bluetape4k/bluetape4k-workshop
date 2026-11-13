package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.invoking
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class EventSourcedEventStreamTest {

    private companion object: KLogging() {
        const val TENANT = "tenant-a"
        val CAMPAIGN_ID: UUID = UUID.fromString("0198f311-8700-7000-8000-000000000011")
        val NOW: Instant = Instant.parse("2026-07-23T00:00:00Z")

        fun descriptor(
            streamPosition: Long = 3,
            globalPosition: Long = 11,
            projectionPosition: Long = 7,
        ): EventSourcedPublicEventDescriptor =
            EventSourcedPublicEventDescriptor(
                campaignId = CAMPAIGN_ID,
                state = "ACTIVE",
                streamPosition = streamPosition,
                globalPosition = globalPosition,
                projectionPosition = projectionPosition,
                lag = globalPosition - projectionPosition,
                observedAt = NOW,
            )
    }

    @Test
    fun `cursor preserves aggregate global and projection positions`() {
        val cursor = EventSourcedStreamCursor.parse("3:11:7").shouldNotBeNull()

        log.debug { "cursor: $cursor" }
        cursor.streamPosition shouldBeEqualTo 3L
        cursor.globalPosition shouldBeEqualTo 11L
        cursor.projectionPosition shouldBeEqualTo 7L
        cursor.toString() shouldBeEqualTo "3:11:7"
    }

    @Test
    fun `stream starts with bounded snapshot and emits heartbeat with the same cursor`() =
        withStream(SequenceSource(descriptor())) { stream ->
            stream.open(TENANT, CAMPAIGN_ID, null).use { subscription ->
                val snapshot = subscription.next(Duration.ZERO).shouldNotBeNull()
                val heartbeat = subscription.next(1.millis()).shouldNotBeNull()

                log.debug { "snapshot: $snapshot" }
                log.debug { "heartbeat: $heartbeat" }

                snapshot.event shouldBeEqualTo "snapshot"
                snapshot.cursor shouldBeEqualTo EventSourcedStreamCursor(3, 11, 7)
                snapshot.data shouldContain "\"campaignId\":\"$CAMPAIGN_ID\""
                snapshot.data shouldNotContain "payload"

                heartbeat.event shouldBeEqualTo "heartbeat"
                heartbeat.cursor shouldBeEqualTo snapshot.cursor
            }
        }

    @Test
    fun `queue overflow replaces stale frames with one terminal authoritative reset`() {
        val source = SequenceSource(
            descriptor(),
            descriptor(streamPosition = 4, globalPosition = 12, projectionPosition = 8),
        )
        withStream(
            source,
            EventSourcedSseProperties(queueSize = 1, pollInterval = 1.millis()),
        ) { stream ->
            stream.open(TENANT, CAMPAIGN_ID, null).use { subscription ->
                await atMost 2.seconds withPollInterval 100.milliseconds untilAsserted {
                    source.readCount.get() shouldBeGreaterThan 1
                }

                val reset = subscription.next(Duration.ZERO).shouldNotBeNull()

                log.debug { "reset: $reset" }
                reset.event shouldBeEqualTo "reset"
                reset.cursor shouldBeEqualTo EventSourcedStreamCursor(4, 12, 8)
                reset.terminal.shouldBeTrue()

                subscription.next(Duration.ZERO).shouldBeNull()
            }
        }
    }

    @Test
    fun `future reconnect cursor is rejected before a subscription is admitted`() =
        withStream(SequenceSource(descriptor())) { stream ->
            assertFailsWith<IllegalArgumentException> {
                stream.open(
                    tenant = TENANT,
                    campaignId = CAMPAIGN_ID,
                    requestedCursor = EventSourcedStreamCursor(4, 12, 8)
                )
            }
        }

    private fun withStream(
        source: EventSourcedEventSource,
        properties: EventSourcedSseProperties = EventSourcedSseProperties(),
        block: (EventSourcedEventStream) -> Unit,
    ) {
        EventSourcedEventStream(source, Jackson.defaultJsonMapper, properties).use(block)
    }

    private class SequenceSource(
        private vararg val descriptors: EventSourcedPublicEventDescriptor,
    ): EventSourcedEventSource {
        val readCount = AtomicInteger()

        override fun read(
            tenant: String,
            campaignId: UUID,
        ): EventSourcedPublicEventDescriptor {
            val index = readCount.getAndIncrement().coerceAtMost(descriptors.lastIndex)
            return descriptors[index]
        }
    }
}
