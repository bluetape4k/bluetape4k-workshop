package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.commerce.voucher.config.VoucherProperties
import io.bluetape4k.workshop.commerce.voucher.config.VoucherSseProperties
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class VoucherEventStreamTest {
    @Test
    fun `two subscribers share one campaign poller until the last cleanup`() =
        withStream(FakeEventSource(), testProperties()) { stream ->
            val campaignId = Uuid.V7.nextId()
            val first = stream.open("tenant", campaignId, null)
            val second = stream.open("tenant", campaignId, null)

            stream.activePollers() shouldBeEqualTo 1
            first.close()
            stream.activePollers() shouldBeEqualTo 1
            second.close()
            second.close()

            second.cleanupInvocationCount() shouldBeEqualTo 1L
            stream.activePollers() shouldBeEqualTo 0
        }

    @Test
    fun `overflow replaces stale queue entries with terminal authoritative reset`() {
        val source =
            FakeEventSource { campaignId, afterId ->
                if (afterId < 2L) {
                    VoucherStreamBatch(snapshot(campaignId, revision = 2), listOf(EventCursor(2, 2) to audit(campaignId, 2)))
                } else {
                    VoucherStreamBatch(snapshot(campaignId, revision = 2), emptyList())
                }
            }
        val properties = testProperties(queueSize = 1, pollInterval = Duration.ofMillis(1))

        withStream(source, properties) { stream ->
            val subscription = stream.open("tenant", Uuid.V7.nextId(), null)
            await atMost Duration.ofSeconds(2) untilAsserted {
                (source.pollCount.get() > 0).shouldBeTrue()
            }

            val output = ByteArrayOutputStream()
            stream.write(subscription, output)

            val encoded = output.toString(Charsets.UTF_8)
            encoded shouldContain "event: reset"
            encoded shouldContain "id: 2:2"
            encoded shouldNotContain "event: snapshot"
            subscription.cleanupInvocationCount() shouldBeEqualTo 1L
            subscription.queueDepth() shouldBeEqualTo 0
            stream.activePollers() shouldBeEqualTo 0
        }
    }

    @Test
    fun `thirty third campaign is rejected and succeeds after capacity returns`() =
        withStream(FakeEventSource(), testProperties(maxCampaigns = 32)) { stream ->
            val subscriptions =
                (1..32).map {
                    stream.open("tenant", Uuid.V7.nextId(), null)
                }.toMutableList()
            val retryCampaign = Uuid.V7.nextId()

            val rejected = runCatching { stream.open("tenant", retryCampaign, null) }.exceptionOrNull()
            rejected.shouldBeInstanceOf<SseCapacityRejected>()
            stream.activePollers() shouldBeEqualTo 32

            subscriptions.removeFirst().close()
            subscriptions += stream.open("tenant", retryCampaign, null)
            stream.activePollers() shouldBeEqualTo 32

            subscriptions.forEach(AutoCloseable::close)
            stream.activePollers() shouldBeEqualTo 0
        }

    @Test
    fun `blocked write times out and invokes cleanup once`() {
        val properties = testProperties(writeTimeout = Duration.ofMillis(25))
        withStream(FakeEventSource(), properties) { stream ->
            val subscription = stream.open("tenant", Uuid.V7.nextId(), null)
            val started = System.nanoTime()

            stream.write(subscription, BlockingOutputStream())

            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            (elapsed < Duration.ofSeconds(5)).shouldBeTrue()
            subscription.cleanupInvocationCount() shouldBeEqualTo 1L
            subscription.queueDepth() shouldBeEqualTo 0
            stream.activePollers() shouldBeEqualTo 0
        }
    }

    @Test
    fun `idle polling backs off to cap and activity restores base interval`() {
        val sequence = AtomicInteger()
        val releaseActivity = CountDownLatch(1)
        val source =
            FakeEventSource { campaignId, _ ->
                val current = sequence.incrementAndGet()
                if (current >= 3) {
                    if (current == 3) releaseActivity.await(2, TimeUnit.SECONDS)
                    VoucherStreamBatch(
                        snapshot(campaignId, revision = current.toLong()),
                        listOf(EventCursor(current.toLong(), current.toLong()) to audit(campaignId, current.toLong())),
                    )
                } else {
                    VoucherStreamBatch(snapshot(campaignId), emptyList())
                }
            }
        val properties =
            testProperties(
                pollInterval = Duration.ofMillis(10),
                maxIdleInterval = Duration.ofMillis(40),
            )

        withStream(source, properties) { stream ->
            val campaignId = Uuid.V7.nextId()
            val subscription = stream.open("tenant", campaignId, null)
            await atMost Duration.ofSeconds(2) untilAsserted {
                (source.pollCount.get() >= 2).shouldBeTrue()
                stream.pollDelay("tenant", campaignId) shouldBeEqualTo Duration.ofMillis(40)
            }
            releaseActivity.countDown()
            await atMost Duration.ofSeconds(2) untilAsserted {
                (source.pollCount.get() >= 3).shouldBeTrue()
                stream.pollDelay("tenant", campaignId) shouldBeEqualTo Duration.ofMillis(10)
            }
            subscription.close()
        }
    }

    private fun withStream(
        source: VoucherEventSource,
        properties: VoucherProperties,
        block: (VoucherEventStream) -> Unit,
    ) {
        VirtualThreads.executorService().use { executor ->
            VoucherEventStream(source, Jackson.defaultJsonMapper, executor, properties).use(block)
        }
    }

    private fun testProperties(
        maxCampaigns: Int = 32,
        queueSize: Int = 32,
        pollInterval: Duration = Duration.ofSeconds(1),
        maxIdleInterval: Duration = Duration.ofSeconds(2),
        writeTimeout: Duration = Duration.ofSeconds(1),
    ): VoucherProperties =
        VoucherProperties(
            sse =
                VoucherSseProperties(
                    maxCampaigns = maxCampaigns,
                    queueSize = queueSize,
                    pollInterval = pollInterval,
                    maxIdleInterval = maxIdleInterval,
                    heartbeatInterval = Duration.ofMillis(100),
                    writeTimeout = writeTimeout,
                ),
        )

    private class FakeEventSource(
        private val nextBatch: (UUID, Long) -> VoucherStreamBatch = { campaignId, _ ->
            VoucherStreamBatch(snapshot(campaignId), emptyList())
        },
    ) : VoucherEventSource {
        val pollCount = AtomicInteger()

        override fun initial(
            tenantId: String,
            campaignId: UUID,
            requestedCursor: EventCursor?,
        ): VoucherStreamInitial = VoucherStreamInitial(snapshot(campaignId), EventCursor(1, 1), false)

        override fun poll(
            tenantId: String,
            campaignId: UUID,
            afterId: Long,
        ): VoucherStreamBatch {
            pollCount.incrementAndGet()
            return nextBatch(campaignId, afterId)
        }
    }

    private class BlockingOutputStream : OutputStream() {
        private val release = CountDownLatch(1)

        override fun write(value: Int) {
            release.await(30, TimeUnit.SECONDS)
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            release.await(30, TimeUnit.SECONDS)
        }
    }

    private companion object {
        fun snapshot(
            campaignId: UUID,
            revision: Long = 1,
        ): CampaignHttpResponse {
            val now = Instant.parse("2026-07-20T00:00:00Z")
            return CampaignHttpResponse(
                campaignId = campaignId,
                state = "ACTIVE",
                revision = revision,
                policyVersion = 1,
                capacity = 10,
                allocatedCount = 0,
                remainingCapacity = 10,
                startsAt = now.minusSeconds(60),
                endsAt = now.plusSeconds(3600),
                observedAt = now,
            )
        }

        fun audit(
            campaignId: UUID,
            revision: Long,
        ): VoucherAuditHttpEvent =
            VoucherAuditHttpEvent(
                aggregateType = "CAMPAIGN",
                aggregateId = campaignId,
                revision = revision,
                reasonCode = "CAMPAIGN_UPDATED",
                policyVersion = 1,
                occurredAt = Instant.parse("2026-07-20T00:00:00Z"),
            )
    }
}
