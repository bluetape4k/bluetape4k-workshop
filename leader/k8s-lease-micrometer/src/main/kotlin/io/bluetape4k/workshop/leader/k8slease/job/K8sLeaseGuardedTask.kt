package io.bluetape4k.workshop.leader.k8slease.job

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.leader.k8slease.config.K8sLeaseMicrometerProperties
import io.bluetape4k.workshop.leader.k8slease.leader.LeaderCoordinator
import io.bluetape4k.workshop.leader.k8slease.metrics.K8sLeaseMetrics
import io.bluetape4k.workshop.leader.k8slease.metrics.LeaseMetricTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.Serializable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

/**
 * Scheduled coroutine task guarded by Kubernetes Lease leadership.
 */
@Service
class K8sLeaseGuardedTask(
    private val coordinator: LeaderCoordinator,
    private val properties: K8sLeaseMicrometerProperties,
    private val metrics: K8sLeaseMetrics,
) {
    val executionCount: AtomicInteger = AtomicInteger()
    internal var failWork: Boolean = false

    private companion object : KLogging()

    /**
     * Spring scheduler entry point. `runBlocking` is limited to the scheduler boundary.
     */
    @Scheduled(fixedDelayString = "\${workshop.leader.k8s.job-fixed-delay:10s}")
    fun runScheduled() {
        runBlocking {
            runOnce()
        }
    }

    /**
     * Executes one guarded task tick and returns a learner-readable report.
     */
    suspend fun runOnce(): LeaderTaskReport {
        val tags = LeaseMetricTags(properties.leaseName, properties.namespace)
        metrics.recordGuardAttempt(tags)
        val started = TimeSource.Monotonic.markNow()

        return try {
            val report = coordinator.runIfLeader(properties.leaseName) {
                metrics.markActive(tags)
                metrics.recordRenewAttempt(tags)
                try {
                    delay(properties.simulatedWorkTime.toKotlinDuration())
                    check(!failWork) { "Simulated workshop task failure" }
                    val count = executionCount.incrementAndGet()
                    metrics.recordTask(tags, "success", started.elapsedNow())
                    log.info { "Kubernetes Lease guarded task executed. lease=${properties.leaseName}, count=$count" }
                    LeaderTaskReport(executed = true, reason = "elected", executionCount = count)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    metrics.recordRenewFailure(tags, "task-failed")
                    metrics.recordTask(tags, "failure", started.elapsedNow())
                    log.warn(e) { "Kubernetes Lease guarded task failed. lease=${properties.leaseName}" }
                    LeaderTaskReport(executed = false, reason = "task-failed", executionCount = executionCount.get())
                } finally {
                    metrics.markInactive(tags)
                }
            }

            if (report == null) {
                metrics.recordSkipped(tags, "not-elected")
                LeaderTaskReport(executed = false, reason = "not-elected", executionCount = executionCount.get())
            } else {
                report
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            metrics.recordSkipped(tags, "backend-error")
            metrics.recordRenewFailure(tags, "backend-error")
            metrics.recordTask(tags, "failure", started.elapsedNow())
            log.warn(e) { "Kubernetes Lease guarded task could not enter leader boundary. lease=${properties.leaseName}" }
            LeaderTaskReport(executed = false, reason = "backend-error", executionCount = executionCount.get())
        }
    }
}

/**
 * Result of one scheduled guard tick.
 */
data class LeaderTaskReport(
    val executed: Boolean,
    val reason: String,
    val executionCount: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
