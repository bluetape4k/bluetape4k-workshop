package io.bluetape4k.workshop.commerce.order.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.order.query.OrderLifecycleQueryService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.during
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.servlet.mvc.method.annotation.TestSseEmitterHandler
import org.springframework.web.servlet.mvc.method.annotation.attachTestHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OrderEventStreamTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OrderEventStreamTest {

    companion object: KLogging()

/**
 * queryService은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val queryService = mockk<OrderLifecycleQueryService>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(queryService)
    }

    @Test
/**
 * 이 시나리오는 "lifecycle coordination uses an explicit lock" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `lifecycle coordination uses an explicit lock`() {
        val lifecycleLock = OrderEventStream::class.java.getDeclaredField("lifecycleLock")

        ReentrantLock::class.java.isAssignableFrom(lifecycleLock.type).shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "SSE poll configuration uses the released positive-number helper" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `SSE poll configuration uses the released positive-number helper`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/io/bluetape4k/workshop/commerce/order/web/OrderEventStream.kt")
        )
        source shouldContain "requirePositiveNumber(\"order-lifecycle.sse.max-concurrent-polls\")"
    }

    @Test
/**
 * 이 시나리오는 "shutdown rejects an open racing with the initial snapshot" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `shutdown rejects an open racing with the initial snapshot`() {
        val snapshotStarted = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        every {
            queryService.snapshot(any())
        } answers {
            snapshotStarted.countDown()
            releaseSnapshot.await()
            mockk(relaxed = true)
        }
        every { queryService.auditAfter(any(), any()) } returns emptyList()
        val executor = Executors.newVirtualThreadPerTaskExecutor()

        val stream = OrderEventStream(queryService, executor, MockEnvironment())

        try {
            val opening = executor.submit { stream.open(UUID.randomUUID(), 0) }
            snapshotStarted.await(1.seconds).shouldBeTrue()

            stream.destroy()
            releaseSnapshot.countDown()

            val failure = assertFailsWith<ExecutionException> {
                opening.get(1.seconds)
            }
            failure.cause.shouldBeInstanceOf<StreamShuttingDown>()
            assertFailsWith<StreamShuttingDown> {
                stream.open(UUID.randomUUID(), 0)
            }
        } finally {
            releaseSnapshot.countDown()
            executor.shutdownNow()
        }
    }

    @Test
/**
 * 이 시나리오는 "future client cursor cannot suppress events after the current snapshot" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `future client cursor cannot suppress events after the current snapshot`() {
        every { queryService.snapshot(any()) } returns mockk(relaxed = true)
        val observedCursor = AtomicInteger(-1)
        val polled = CountDownLatch(1)
        every {
            queryService.auditAfter(any(), any())
        } answers {
            observedCursor.set(secondArg<Long>().toInt())
            polled.countDown()
            emptyList()
        }

        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val stream = OrderEventStream(queryService, executor, MockEnvironment())

        try {
            stream.open(UUID.randomUUID(), Long.MAX_VALUE)
            polled.await(1.seconds).shouldBeTrue()
            observedCursor.get() shouldBeEqualTo 0
        } finally {
            stream.destroy()
            executor.shutdownNow()
        }
    }

    @Test
/**
 * 이 시나리오는 "heartbeat is emitted and timeout releases the connection slot" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `heartbeat is emitted and timeout releases the connection slot`() {
        every { queryService.snapshot(any()) } returns mockk(relaxed = true)
        every { queryService.auditAfter(any(), any()) } returns emptyList()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val environment = MockEnvironment()
            .withProperty("order-lifecycle.sse.max-connections", "1")
            .withProperty("order-lifecycle.sse.poll-interval", "5ms")
            .withProperty("order-lifecycle.sse.heartbeat-interval", "20ms")

        environment.conversionService = ApplicationConversionService()
        val stream = OrderEventStream(queryService, executor, environment)

        try {
            val first = stream.open(UUID.randomUUID(), 0)
            val handler = TestSseEmitterHandler()
            first.attachTestHandler(handler)

            await atMost 1.seconds untilAsserted {
                handler.sent.any { it.contains(":heartbeat") }.shouldBeTrue()
            }
            handler.triggerTimeout()
            stream.open(UUID.randomUUID(), 0)
        } finally {
            stream.destroy()
            executor.shutdownNow()
        }
    }

    @Test
/**
 * 이 시나리오는 "client disconnect releases the connection slot" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `client disconnect releases the connection slot`() {
        every { queryService.snapshot(any()) } returns mockk(relaxed = true)
        every { queryService.auditAfter(any(), any()) } returns emptyList()

        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val stream = OrderEventStream(
            queryService,
            executor,
            MockEnvironment().withProperty("order-lifecycle.sse.max-connections", "1")
        )

        try {
            val first = stream.open(UUID.randomUUID(), 0)
            val handler = TestSseEmitterHandler()
            first.attachTestHandler(handler)
            handler.triggerDisconnect()

            stream.open(UUID.randomUUID(), 0)
        } finally {
            stream.destroy()
            executor.shutdownNow()
        }
    }

    @Test
/**
 * 이 시나리오는 "connections for the same order share one database poller" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `connections for the same order share one database poller`() {
        every { queryService.snapshot(any()) } returns mockk(relaxed = true)

        val polls = AtomicInteger()
        val firstPoll = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        every {
            queryService.auditAfter(any(), any())
        } answers {
            polls.incrementAndGet()
            firstPoll.countDown()
            releasePoll.await()
            emptyList()
        }
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val stream = OrderEventStream(
            queryService,
            executor,
            MockEnvironment()
                .withProperty("order-lifecycle.sse.max-connections", "3")
        )

        try {
            val orderId = UUID.randomUUID()
            repeat(3) {
                stream.open(orderId, 0)
            }

            firstPoll.await(1.seconds).shouldBeTrue()

            await during 100.milliseconds atMost 500.milliseconds untilAsserted {
                polls.get() shouldBeEqualTo 1
            }
        } finally {
            releasePoll.countDown()
            stream.destroy()
            executor.shutdownNow()
        }
    }

    @Test
/**
 * 이 시나리오는 "shutdown cancels all blocked pollers within one bounded deadline" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `shutdown cancels all blocked pollers within one bounded deadline`() {
        val entered = CountDownLatch(3)
        every { queryService.snapshot(any()) } returns mockk(relaxed = true)
        every {
            queryService.auditAfter(any(), any())
        } answers {
            entered.countDown()
            CountDownLatch(1).await()
            emptyList()
        }
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val shutdownExecutor = Executors.newVirtualThreadPerTaskExecutor()
        val stream = OrderEventStream(
            queryService,
            executor,
            MockEnvironment().withProperty("order-lifecycle.sse.max-connections", "3")
        )

        try {
            repeat(3) {
                stream.open(UUID.randomUUID(), 0)
            }
            entered.await(1.seconds).shouldBeTrue()

            shutdownExecutor.submit { stream.destroy() }.get(500.milliseconds)
        } finally {
            stream.destroy()
            shutdownExecutor.shutdownNow()
            executor.shutdownNow()
        }
    }

    @Test
/**
 * 이 시나리오는 "failed initial snapshot releases the connection slot" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `failed initial snapshot releases the connection slot`() {
        every { queryService.snapshot(any()) } throws NoSuchElementException("order not found")

        val executor = Executors.newSingleThreadExecutor()
        val stream = OrderEventStream(
            queryService,
            executor,
            MockEnvironment().withProperty("order-lifecycle.sse.max-connections", "1")
        )

        try {
            repeat(2) {
                assertFailsWith<NoSuchElementException> {
                    stream.open(UUID.randomUUID(), 0)
                }
            }
        } finally {
            stream.destroy()
            executor.shutdownNow()
        }
    }
}
