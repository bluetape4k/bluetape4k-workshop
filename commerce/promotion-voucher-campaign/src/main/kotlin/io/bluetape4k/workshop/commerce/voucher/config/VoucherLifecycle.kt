package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationScheduler
import io.bluetape4k.workshop.commerce.voucher.web.VoucherEventStream
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class VoucherShutdownEvent {
    READINESS_DOWN,
    REJECT_NEW,
    STOP_WORKER,
    AWAIT_DB,
    STOP_SSE,
    RELEASE_LEADER,
    CLOSE_REDIS,
    CLOSE_EXECUTOR,
}

internal enum class VoucherShutdownReason {
    CLEAN,
    DB_DRAIN_DEADLINE,
}

/** 단일 주입 grace deadline을 사용하는 결정적 shutdown state machine입니다. */
internal class VoucherLifecycleCoordinator(
    private val gate: DatabasePermitGate,
    private val readinessDown: () -> Unit = {},
    private val stopWorker: () -> Unit,
    private val stopSse: () -> Unit,
    private val releaseLeader: () -> Unit,
    private val closeRedis: () -> Unit,
    private val closeExecutor: () -> Unit,
    private val graceDeadline: Duration = Duration.ofSeconds(30),
) {
    private val shutdown = AtomicBoolean()
    private val eventLock = ReentrantLock()
    private val eventRecorded = eventLock.newCondition()
    private val recordedEvents = ArrayList<VoucherShutdownEvent>()

    @Volatile
    private var reason = VoucherShutdownReason.CLEAN

    init {
        require(!graceDeadline.isNegative && !graceDeadline.isZero) { "graceDeadline must be positive" }
    }

    fun shutdown() {
        if (!shutdown.compareAndSet(false, true)) return
        val deadline = System.nanoTime() + graceDeadline.toNanos()
        step(VoucherShutdownEvent.READINESS_DOWN, readinessDown)
        step(VoucherShutdownEvent.REJECT_NEW, gate::beginShutdown)
        step(VoucherShutdownEvent.STOP_WORKER, stopWorker)
        record(VoucherShutdownEvent.AWAIT_DB)
        if (!gate.awaitDrained(remaining(deadline))) {
            reason = VoucherShutdownReason.DB_DRAIN_DEADLINE
            log.warn { "voucher_shutdown_forced reason=$reason" }
        }
        step(VoucherShutdownEvent.STOP_SSE, stopSse)
        step(VoucherShutdownEvent.RELEASE_LEADER, releaseLeader)
        step(VoucherShutdownEvent.CLOSE_REDIS, closeRedis)
        step(VoucherShutdownEvent.CLOSE_EXECUTOR, closeExecutor)
        log.info { "voucher_shutdown_completed reason=$reason" }
    }

    fun events(): List<VoucherShutdownEvent> = eventLock.withLock { recordedEvents.toList() }

    fun forcedReason(): VoucherShutdownReason = reason

    fun awaitEvent(
        expected: VoucherShutdownEvent,
        timeout: Duration,
    ): Boolean {
        var remaining = timeout.toNanos()
        eventLock.withLock {
            while (expected !in recordedEvents && remaining > 0) {
                remaining = eventRecorded.awaitNanos(remaining)
            }
            return expected in recordedEvents
        }
    }

    private fun step(
        event: VoucherShutdownEvent,
        action: () -> Unit,
    ) {
        record(event)
        action()
    }

    private fun record(event: VoucherShutdownEvent) {
        eventLock.withLock {
            recordedEvents += event
            eventRecorded.signalAll()
        }
    }

    private fun remaining(deadline: Long): Duration =
        Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0))

    companion object : KLogging()
}

/** Spring이 infrastructure bean을 destroy하기 전에 application-owned shutdown sequence를 실행합니다. */
@Component
internal class VoucherLifecycle(
    applicationContext: ApplicationContext,
    gate: DatabasePermitGate,
    scheduler: ObjectProvider<VoucherReconciliationScheduler>,
    streams: VoucherEventStream,
    redis: ObjectProvider<VoucherRedisResources>,
    executorShutdown: VoucherExecutorShutdown,
) : SmartLifecycle {
    private val running = AtomicBoolean()
    private val coordinator =
        VoucherLifecycleCoordinator(
            gate = gate,
            readinessDown = {
                AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)
            },
            stopWorker = { scheduler.ifAvailable?.stop() },
            stopSse = streams::close,
            releaseLeader = { log.info { "voucher_leader_release_completed" } },
            closeRedis = { redis.ifAvailable?.close() },
            closeExecutor = executorShutdown::close,
        )

    override fun start() {
        running.set(true)
    }

    override fun stop(callback: Runnable) {
        if (running.compareAndSet(true, false)) coordinator.shutdown()
        callback.run()
    }

    override fun stop() = stop {}

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 100

    internal fun events(): List<VoucherShutdownEvent> = coordinator.events()

    companion object : KLogging()
}
