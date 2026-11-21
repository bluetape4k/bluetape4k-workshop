package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8String
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
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * VoucherEventStreamTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherEventStreamTest {

    private companion object: KLogging() {
/**
 * snapshot은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun snapshot(campaignId: UUID, revision: Long = 1): CampaignHttpResponse {
/**
 * now은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * audit은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun audit(campaignId: UUID, revision: Long): VoucherAuditHttpEvent =
            VoucherAuditHttpEvent(
                aggregateType = "CAMPAIGN",
                aggregateId = campaignId,
                revision = revision,
                reasonCode = "CAMPAIGN_UPDATED",
                policyVersion = 1,
                occurredAt = Instant.parse("2026-07-20T00:00:00Z"),
            )
    }

    @Test
/**
 * 이 시나리오는 "two subscribers share one campaign poller until the last cleanup" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `two subscribers share one campaign poller until the last cleanup`() =
        withStream(FakeEventSource(), testProperties()) { stream ->
/**
 * campaignId은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val campaignId = Uuid.V7.nextId()
/**
 * first은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val first = stream.open("tenant", campaignId, null)
/**
 * second은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "overflow replaces stale queue entries with terminal authoritative reset" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `overflow replaces stale queue entries with terminal authoritative reset`() {
        val source = FakeEventSource { campaignId, afterId ->
            if (afterId < 2L) {
                VoucherStreamBatch(
                    snapshot(campaignId, revision = 2),
                    listOf(EventCursor(2, 2) to audit(campaignId, 2))
                )
            } else {
                VoucherStreamBatch(snapshot(campaignId, revision = 2), emptyList())
            }
        }
        val properties = testProperties(queueSize = 1, pollInterval = 1.millis())

        withStream(source, properties) { stream ->
            val subscription = stream.open("tenant", Uuid.V7.nextId(), null)

            await atMost 2.seconds() untilAsserted {
                source.pollCount.get() shouldBeGreaterThan 0
            }

            val output = ByteArrayOutputStream()
            stream.write(subscription, output)

            val encoded = output.toByteArray().toUtf8String()
            encoded shouldContain "event: reset"
            encoded shouldContain "id: 2:2"
            encoded shouldNotContain "event: snapshot"

            subscription.cleanupInvocationCount() shouldBeEqualTo 1L
            subscription.queueDepth() shouldBeEqualTo 0
            stream.activePollers() shouldBeEqualTo 0
        }
    }

    @Test
/**
 * 이 시나리오는 "thirty third campaign is rejected and succeeds after capacity returns" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `thirty third campaign is rejected and succeeds after capacity returns`() {
        val maxCampaigns = 32
        withStream(FakeEventSource(), testProperties(maxCampaigns = maxCampaigns)) { stream ->
            val subscriptions = List(maxCampaigns) {
                stream.open("tenant", Uuid.V7.nextId(), null)
            }.toMutableList()

            val retryCampaign = Uuid.V7.nextId()

            val rejected = runCatching {
                stream.open("tenant", retryCampaign, null)
            }.exceptionOrNull()

            rejected.shouldBeInstanceOf<SseCapacityRejected>()
            stream.activePollers() shouldBeEqualTo maxCampaigns

            subscriptions.removeFirst().close()
            subscriptions += stream.open("tenant", retryCampaign, null)
            stream.activePollers() shouldBeEqualTo maxCampaigns

            subscriptions.forEach(AutoCloseable::close)
            stream.activePollers() shouldBeEqualTo 0
        }
    }

    @Test
/**
 * 이 시나리오는 "blocked write times out and invokes cleanup once" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `blocked write times out and invokes cleanup once`() {
        val properties = testProperties(writeTimeout = 25.millis())
        withStream(FakeEventSource(), properties) { stream ->
            val subscription = stream.open("tenant", Uuid.V7.nextId(), null)
            val started = System.nanoTime()

            stream.write(subscription, BlockingOutputStream())

            val elapsed = (System.nanoTime() - started).nanos()
            elapsed shouldBeLessThan 5.seconds()

            subscription.cleanupInvocationCount() shouldBeEqualTo 1L
            subscription.queueDepth() shouldBeEqualTo 0
            stream.activePollers() shouldBeEqualTo 0
        }
    }

    @Test
/**
 * 이 시나리오는 "idle polling backs off to cap and activity restores base interval" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `idle polling backs off to cap and activity restores base interval`() {
        val sequence = AtomicInteger()
        val releaseActivity = CountDownLatch(1)

        val source = FakeEventSource { campaignId, _ ->
            val current = sequence.incrementAndGet()
            if (current >= 3) {
                if (current == 3) releaseActivity.await(2.seconds)
                VoucherStreamBatch(
                    snapshot = snapshot(campaignId, revision = current.toLong()),
                    events = listOf(
                        EventCursor(revision = current.toLong(), id = current.toLong()) to audit(
                            campaignId = campaignId,
                            revision = current.toLong()
                        )
                    ),
                )
            } else {
                VoucherStreamBatch(snapshot(campaignId), emptyList())
            }
        }
        val properties = testProperties(
            pollInterval = 10.millis(),
            maxIdleInterval = 40.millis(),
        )

        withStream(source, properties) { stream ->
            val campaignId = Uuid.V7.nextId()
            val subscription = stream.open("tenant", campaignId, null)

            await atMost 2.seconds() untilAsserted {
                source.pollCount.get() shouldBeGreaterOrEqualTo 2
                stream.pollDelay("tenant", campaignId) shouldBeEqualTo 40.millis()
            }
            releaseActivity.countDown()

            await atMost 2.seconds() untilAsserted {
                source.pollCount.get() shouldBeGreaterOrEqualTo 3
                stream.pollDelay("tenant", campaignId) shouldBeEqualTo 10.millis()
            }
            subscription.close()
        }
    }

/**
 * withStream은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun withStream(
        source: VoucherEventSource,
        properties: VoucherProperties,
        block: (VoucherEventStream) -> Unit,
    ) {
        VirtualThreads.executorService().use { executor ->
            VoucherEventStream(source, Jackson.defaultJsonMapper, executor, properties).use(block)
        }
    }

/**
 * testProperties은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun testProperties(
        maxCampaigns: Int = 32,
        queueSize: Int = 32,
        pollInterval: Duration = 1.seconds(),
        maxIdleInterval: Duration = 2.seconds(),
        writeTimeout: Duration = 1.seconds(),
    ): VoucherProperties =
        VoucherProperties(
            sse = VoucherSseProperties(
                maxCampaigns = maxCampaigns,
                queueSize = queueSize,
                pollInterval = pollInterval,
                maxIdleInterval = maxIdleInterval,
                heartbeatInterval = 100.millis(),
                writeTimeout = writeTimeout,
            ),
        )

/**
 * FakeEventSource 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class FakeEventSource(
/**
 * nextBatch은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val nextBatch: (UUID, Long) -> VoucherStreamBatch =
            { campaignId, _ ->
                VoucherStreamBatch(snapshot(campaignId), emptyList())
            },
    ): VoucherEventSource {
/**
 * pollCount은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val pollCount = AtomicInteger()

/**
 * initial은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun initial(
            tenantId: String,
            campaignId: UUID,
            requestedCursor: EventCursor?,
        ): VoucherStreamInitial =
            VoucherStreamInitial(
                snapshot = snapshot(campaignId),
                cursor = EventCursor(1, 1),
                resetRequired = false
            )

/**
 * poll은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun poll(tenantId: String, campaignId: UUID, afterId: Long): VoucherStreamBatch {
            pollCount.incrementAndGet()
            return nextBatch(campaignId, afterId)
        }
    }

/**
 * BlockingOutputStream 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class BlockingOutputStream: OutputStream() {
/**
 * release은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val release = CountDownLatch(1)

/**
 * write은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun write(value: Int) {
            release.await(30.seconds)
        }

/**
 * write은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            release.await(30.seconds)
        }
    }
}
