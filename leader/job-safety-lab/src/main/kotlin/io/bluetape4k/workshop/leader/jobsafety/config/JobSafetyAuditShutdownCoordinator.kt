package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.leader.audit.LeaderAuditExporter
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * audit transport와 실행기의 context-close 순서 및 aggregate deadline을 소유합니다.
 *
 * Spring resource bean은 implicit destroy를 비활성화하고 이 coordinator 하나만
 * `close` destroy method로 등록해야 합니다. 각 단계는 같은 monotonic deadline의 남은
 * 시간만 사용하므로 resource별 timeout을 합산하지 않습니다.
 */
class JobSafetyAuditShutdownCoordinator(
    private val shutdownTimeout: Duration,
    private val subscription: AutoCloseable,
    private val exporter: LeaderAuditExporter,
    private val clientLifecycle: JobSafetyAuditHttpClientLifecycle,
    private val scheduler: ScheduledThreadPoolExecutor,
    private val executor: ExecutorService,
    private val scope: JobSafetyAuditScope,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onStep: (String) -> Unit = {},
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    init {
        require(!shutdownTimeout.isNegative && !shutdownTimeout.isZero) {
            "shutdownTimeout must be positive: $shutdownTimeout"
        }
    }

    /** 모든 owned resource를 idempotently bounded 순서로 종료합니다. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        val deadline = checkedDeadline(nanoTime(), shutdownTimeout)
        closeQuietly("subscription.close", subscription::close)
        closeQuietly("exporter.close", exporter::close)

        closeQuietly("client.shutdownNow", clientLifecycle::shutdownNow)
        awaitQuietly("client.awaitTermination", deadline) {
            clientLifecycle.awaitTermination(remaining(deadline))
        }

        closeQuietly("scheduler.shutdownNow") { scheduler.shutdownNow() }
        awaitQuietly("scheduler.awaitTermination", deadline) {
            scheduler.awaitTermination(remaining(deadline).toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)
        }

        closeQuietly("executor.shutdownNow") { executor.shutdownNow() }
        awaitQuietly("executor.awaitTermination", deadline) {
            executor.awaitTermination(remaining(deadline).toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)
        }

        closeQuietly("scope.close", scope::close)
    }

    private fun checkedDeadline(start: Long, timeout: Duration): Long =
        try {
            Math.addExact(start, timeout.toNanos())
        } catch (error: ArithmeticException) {
            Long.MAX_VALUE
        }

    private fun remaining(deadline: Long): Duration {
        val nanos = (deadline - nanoTime()).coerceAtLeast(0L)
        return Duration.ofNanos(nanos)
    }

    private fun closeQuietly(step: String, action: () -> Unit) {
        onStep(step)
        try {
            action()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // 종료 경로에서는 후속 resource 정리를 계속 수행합니다.
        }
    }

    private fun awaitQuietly(step: String, deadline: Long, action: () -> Unit) {
        onStep(step)
        if (nanoTime() >= deadline) return
        try {
            action()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // 종료 경로에서는 후속 resource 정리를 계속 수행합니다.
        }
    }
}
