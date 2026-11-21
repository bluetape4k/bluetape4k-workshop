package io.bluetape4k.workshop.commerce.order.web

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.order.query.OrderLifecycleQueryService
import org.springframework.beans.factory.DisposableBean
import org.springframework.core.env.Environment
import org.springframework.core.env.getProperty
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * StreamCapacityExceeded은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class StreamCapacityExceeded: IllegalStateException("SSE connection capacity exceeded")

/**
 * StreamShuttingDown은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class StreamShuttingDown: IllegalStateException("SSE stream is shutting down")
/**
 * OrderEventStream은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property queries 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property orderLifecycleExecutor 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
internal class OrderEventStream(
    /**
     * `queries`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val queries: OrderLifecycleQueryService,
    /**
     * `orderLifecycleExecutor`은 주문·결제·재고·배송에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val orderLifecycleExecutor: ExecutorService,
    environment: Environment,
): DisposableBean {

    companion object: KLogging()
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */

    private val timeout = environment.getProperty<Duration>(
        "order-lifecycle.sse.timeout",
        60.seconds(),
    )
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val pollInterval = environment.getProperty<Duration>(
        "order-lifecycle.sse.poll-interval",
        250.millis()
    )
    /**
     * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
     */
    private val heartbeatInterval = environment.getProperty<Duration>(
        "order-lifecycle.sse.heartbeat-interval",
        15.seconds()
    )
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val maxConnections = environment.getProperty<Int>("order-lifecycle.sse.max-connections", 1_000)
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val _maxConcurrentPolls = environment.getProperty<Int>("order-lifecycle.sse.max-concurrent-polls", 4)
        .requirePositiveNumber("order-lifecycle.sse.max-concurrent-polls")
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val maxConcurrentPolls: Int
        get() = _maxConcurrentPolls
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val pollPermits = Semaphore(maxConcurrentPolls, true)
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val activeConnections = AtomicInteger()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val connections = ConcurrentHashMap<SseEmitter, StreamConnection>()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val feeds = ConcurrentHashMap<UUID, OrderFeed>()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val draining = AtomicBoolean()
    /**
     * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val lifecycleLock = ReentrantLock()

    /**
     * open은 주문 라이프사이클과 fulfillment 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param orderId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param lastEventId 처리할 주문 라이프사이클과 fulfillment 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun open(
        orderId: UUID,
        lastEventId: Long,
    ): SseEmitter {
        val emitter = SseEmitter(timeout.toMillis())
        val connection = StreamConnection(emitter, orderId, lastEventId, heartbeatInterval)

        lifecycleLock.withLock {
            if (draining.get()) throw StreamShuttingDown()
            if (activeConnections.incrementAndGet() > maxConnections) {
                activeConnections.decrementAndGet()
                log.warn { "sse_capacity_exceeded orderId=$orderId maxConnections=$maxConnections" }
                throw StreamCapacityExceeded()
            }
            connections[emitter] = connection
        }
        val release: () -> Unit = {
            release(connection)
        }
        emitter.onCompletion(release)
        emitter.onTimeout(release)
        emitter.onError { release() }

        try {
            val snapshot = queries.snapshot(orderId)
            if (draining.get()) throw StreamShuttingDown()
            // snapshot은 이미 현재 audit view를 포함하므로 해당 cursor가 authoritative입니다.
            // 임의의 미래 Last-Event-ID를 신뢰하면 이후의 유효한 event가 억제됩니다.
            val snapshotCursor = snapshot.audit.maxOfOrNull { it.id } ?: 0L
            connection.cursor.set(snapshotCursor)
            emitter.send(
                SseEmitter
                    .event()
                    .id(snapshotCursor.toString())
                    .name("snapshot")
                    .reconnectTime(1_000)
                    .data(snapshot)
            )

            lifecycleLock.withLock {
                if (draining.get()) throw StreamShuttingDown()
                val feed = feeds.compute(orderId) { _, current ->
                    current?.takeUnless { it.closed.get() } ?: OrderFeed(orderId)
                }.checkNotNull { "feed creation must return an OrderFeed" }
                connection.feed = feed
                feed.connections[emitter] = connection
                start(feed)
            }
            log.info {
                "sse_connection_opened orderId=$orderId lastEventId=$lastEventId cursor=$snapshotCursor " +
                        "activeConnections=${activeConnections.get()}"
            }
        } catch (failure: Exception) {
            release()
            log.warn(failure) { "sse_connection_open_failed orderId=$orderId lastEventId=$lastEventId" }
            throw failure
        }
        return emitter
    }

    /**
     * destroy은 주문 라이프사이클과 fulfillment의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun destroy() {
        val (drainingConnections, drainingFeeds) = lifecycleLock.withLock {
            if (!draining.compareAndSet(false, true)) return
            val registeredConnections = connections.values.toList()

            registeredConnections.forEach { connection ->
                if (connection.closed.compareAndSet(false, true)) {
                    activeConnections.decrementAndGet()
                }
            }
            val registeredFeeds = feeds.values.toList()
            registeredFeeds.forEach { it.closed.set(true) }
            registeredConnections to registeredFeeds
        }

        // emitter를 완료하기 전에 모든 poller를 취소해,
        // 연결된 client 수와 무관하게 shutdown이 하나의 bounded deadline을 갖도록 합니다.
        drainingFeeds.forEach {
            it.future?.cancel(true)
        }
        drainingConnections.forEach { it.emitter.complete() }
        connections.clear()
        feeds.clear()
        log.info { "sse_stream_shutdown drainedConnections=${drainingConnections.size}" }
    }

    /**
     * start은 주문 라이프사이클과 fulfillment 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param feed 업무 처리에 필요한 feed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun start(feed: OrderFeed) {
        if (!feed.started.compareAndSet(false, true)) return
        feed.future = orderLifecycleExecutor.submit { poll(feed) }
        if (feed.closed.get()) feed.future?.cancel(true)
    }

    /**
     * poll은 주문 라이프사이클과 fulfillment의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param feed 업무 처리에 필요한 feed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun poll(feed: OrderFeed) {
        try {
            while (!feed.closed.get()) {
                val subscribers = feed.connections.values.filterNot { it.closed.get() }
                if (subscribers.isEmpty()) return

                val cursor = subscribers.minOf { it.cursor.get() }
                pollPermits.acquire()
                val events = try {
                    queries.auditAfter(feed.orderId, cursor)
                } finally {
                    pollPermits.release()
                }
                val now = System.nanoTime()

                subscribers.forEach { connection ->
                    if (!connection.closed.get()) {
                        try {
                            events.asSequence()
                                .filter { it.id > connection.cursor.get() }
                                .forEach { event ->
                                    connection.emitter.send(
                                        SseEmitter
                                            .event()
                                            .id(event.id.toString())
                                            .name("audit")
                                            .data(event)
                                    )
                                    connection.cursor.set(event.id)
                                }
                            if (now >= connection.heartbeatAt.get()) {
                                connection.emitter.send(SseEmitter.event().comment("heartbeat"))
                                connection.heartbeatAt.set(now + heartbeatInterval.toNanos())
                            }
                        } catch (failure: Exception) {
                            log.warn(failure) {
                                "sse_connection_failed orderId=${feed.orderId} cursor=${connection.cursor.get()}"
                            }
                            connection.emitter.completeWithError(failure)
                            release(connection)
                        }
                    }
                }
                Thread.sleep(pollInterval)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            log.warn(failure) { "sse_feed_failed orderId=${feed.orderId}" }
            feed.connections.values.forEach { connection ->
                connection.emitter.completeWithError(failure)
                release(connection)
            }
        } finally {
            feeds.remove(feed.orderId, feed)
            feed.connections.values.forEach { connection ->
                connection.emitter.complete()
                release(connection)
            }
        }
    }

    /**
     * release은 주문 라이프사이클과 fulfillment의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun release(connection: StreamConnection) {
        if (connection.closed.compareAndSet(false, true)) {
            val remaining = activeConnections.decrementAndGet()
            log.debug {
                "sse_connection_released orderId=${connection.orderId} activeConnections=$remaining"
            }
        }
        connections.remove(connection.emitter, connection)
        connection.feed?.let { feed ->
            feed.connections.remove(connection.emitter, connection)
            if (feed.connections.isEmpty() && feed.closed.compareAndSet(false, true)) {
                feeds.remove(feed.orderId, feed)
                feed.future?.cancel(true)
            }
        }
    }
    /**
     * StreamConnection은 주문·fulfillment에서 사용하는 업무 객체입니다.
     * @property emitter 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
     * @property orderId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
     */


    private class StreamConnection(
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val emitter: SseEmitter,
        /**
         * `orderId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val orderId: UUID,
        initialCursor: Long,
        heartbeatInterval: Duration,
    ) {
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val closed = AtomicBoolean()
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val cursor = AtomicLong(initialCursor)
        /**
         * 주문·fulfillment의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 같은 clock 기준을 사용합니다.
         */
        val heartbeatAt = AtomicLong(System.nanoTime() + heartbeatInterval.toNanos())
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */

        @Volatile
        var feed: OrderFeed? = null
    }
    /**
     * OrderFeed은 주문·fulfillment에서 사용하는 업무 객체입니다.
     * @property orderId 업무 객체를 연결하는 불변 식별자입니다. 생성 이후 변경하지 않아 저장·API·이벤트의 대상을 일치시킵니다.
     */


    private class OrderFeed(
        /**
         * `orderId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
         *
         * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
         */
        val orderId: UUID,
    ) {
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val started = AtomicBoolean()
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val closed = AtomicBoolean()
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val connections = ConcurrentHashMap<SseEmitter, StreamConnection>()
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */

        @Volatile
        var future: Future<*>? = null
    }
}
