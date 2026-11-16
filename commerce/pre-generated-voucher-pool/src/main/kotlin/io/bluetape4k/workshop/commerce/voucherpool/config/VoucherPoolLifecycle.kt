package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.requireZeroOrPositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

internal enum class VoucherPoolShutdownEvent {
    READINESS_DOWN,
    COMMANDS_AND_SSE_REJECTED,
    TRIGGERS_STOPPED,
    CLAIMS_STOPPED,
    TRANSACTION_DRAIN,
    SSE_AND_POLLER_CLOSED,
    ADVISORY_RESOURCES_CLOSED,
    EXECUTOR_CLOSED,
    DATASOURCE_CLOSED,
}

internal enum class VoucherPoolShutdownReason {
    CLEAN,
    TRANSACTION_DRAIN_TIMEOUT,
    PHASE_TIMEOUT,
    PHASE_FAILED,
    TOTAL_TIMEOUT,
}

internal class VoucherPoolShutdownDeadlines(
    val transactionDrain: Duration = DEFAULT_TRANSACTION_DRAIN_SECONDS.seconds(),
    val claimRelease: Duration = DEFAULT_CLAIM_RELEASE_SECONDS.seconds(),
    val phase: Duration = DEFAULT_PHASE_SECONDS.seconds(),
    val total: Duration = DEFAULT_TOTAL_SECONDS.seconds(),
) {

    private companion object: KLogging() {
        const val DEFAULT_TRANSACTION_DRAIN_SECONDS = 12L
        const val DEFAULT_CLAIM_RELEASE_SECONDS = 5L
        const val DEFAULT_PHASE_SECONDS = 5L
        const val DEFAULT_TOTAL_SECONDS = 45L
    }

    init {
        transactionDrain.requirePositiveDuration("transactionDrain")
        claimRelease.requirePositiveDuration("claimRelease")
        phase.requirePositiveDuration("phase")
        total.requirePositiveDuration("total")

        require(total >= transactionDrain + claimRelease) {
            "total shutdown deadline must include drain and claim release"
        }
    }
}

@Suppress("TooManyFunctions")
internal interface VoucherPoolLifecycleActions {
    fun readinessDown()
    fun rejectCommandsAndSse()
    fun stopTriggers()
    fun stopClaims()
    fun awaitTransactions(timeout: Duration): Boolean
    fun cancelTransactions()
    fun verifyRollback(): Boolean
    fun awaitClaimRelease(timeout: Duration): Boolean
    fun closeSseAndPoller()
    fun closeAdvisoryResources()
    fun closeExecutor()
    fun closeDataSource()
}

/** infrastructure teardown 전에 application-owned SSE subscription과 공유 poller를 닫습니다. */
internal fun interface VoucherPoolStreamShutdown {
    fun closeSseAndPoller()
}

/** 검증용 injected deadline을 가진 deterministic, idempotent shutdown coordinator입니다. */
internal class VoucherPoolLifecycleCoordinator(
    private val actions: VoucherPoolLifecycleActions,
    private val deadlines: VoucherPoolShutdownDeadlines = VoucherPoolShutdownDeadlines(),
) {

    companion object: KLogging()

    private val shuttingDown = AtomicBoolean()
    private val eventLock = ReentrantLock()
    private val recordedEvents = ArrayList<VoucherPoolShutdownEvent>()

    @Volatile
    private var shutdownReason = VoucherPoolShutdownReason.CLEAN

    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        val totalDeadline = System.nanoTime() + deadlines.total.toNanos()

        phase(VoucherPoolShutdownEvent.READINESS_DOWN, totalDeadline, actions::readinessDown)
        phase(VoucherPoolShutdownEvent.COMMANDS_AND_SSE_REJECTED, totalDeadline, actions::rejectCommandsAndSse)
        phase(VoucherPoolShutdownEvent.TRIGGERS_STOPPED, totalDeadline, actions::stopTriggers)
        phase(VoucherPoolShutdownEvent.CLAIMS_STOPPED, totalDeadline, actions::stopClaims)

        drain(totalDeadline)

        phase(VoucherPoolShutdownEvent.SSE_AND_POLLER_CLOSED, totalDeadline, actions::closeSseAndPoller)
        phase(VoucherPoolShutdownEvent.ADVISORY_RESOURCES_CLOSED, totalDeadline, actions::closeAdvisoryResources)
        phase(VoucherPoolShutdownEvent.EXECUTOR_CLOSED, totalDeadline, actions::closeExecutor)
        phase(VoucherPoolShutdownEvent.DATASOURCE_CLOSED, totalDeadline, actions::closeDataSource)

        log.info { "voucher_pool_shutdown_completed reason=$shutdownReason" }
    }

    fun events(): List<VoucherPoolShutdownEvent> = eventLock.withLock { recordedEvents.toList() }

    fun reason(): VoucherPoolShutdownReason = shutdownReason

    private fun drain(totalDeadline: Long) {
        record(VoucherPoolShutdownEvent.TRANSACTION_DRAIN)
        val drainBudget = minOf(deadlines.transactionDrain, remaining(totalDeadline))

        if (drainBudget.isZero || !actions.awaitTransactions(drainBudget)) {
            shutdownReason = VoucherPoolShutdownReason.TRANSACTION_DRAIN_TIMEOUT
            log.warn { "voucher_pool_shutdown_forced reason=$shutdownReason" }

            runBounded(totalDeadline, actions::cancelTransactions)
            runBounded(totalDeadline) { check(actions.verifyRollback()) { "transaction rollback was not verified" } }

            val releaseBudget = minOf(deadlines.claimRelease, remaining(totalDeadline))
            runBounded(totalDeadline) {
                check(!releaseBudget.isZero && actions.awaitClaimRelease(releaseBudget)) {
                    "worker claim release deadline exceeded"
                }
            }
        }
    }

    private fun phase(event: VoucherPoolShutdownEvent, totalDeadline: Long, action: () -> Unit) {
        record(event)
        runBounded(totalDeadline, action)
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun runBounded(totalDeadline: Long, action: () -> Unit): Boolean {
        val budget = minOf(deadlines.phase, remaining(totalDeadline))
        if (budget.isZero) {
            updateReason(VoucherPoolShutdownReason.TOTAL_TIMEOUT)
            return false
        }
        val completed = CountDownLatch(1)
        var failure: Throwable? = null
        val thread = Thread.ofVirtual().name("voucher-pool-shutdown-phase").start {
            try {
                action()
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                completed.countDown()
            }
        }

        val finished = completed.await(budget.toKotlinDuration())
        if (!finished) {
            thread.interrupt()
            updateReason(VoucherPoolShutdownReason.PHASE_TIMEOUT)
            return false
        }
        if (failure != null) {
            updateReason(VoucherPoolShutdownReason.PHASE_FAILED)
            log.warn { "voucher_pool_shutdown_phase_failed failure=${failure.javaClass.simpleName}" }
            return false
        }
        return true
    }

    private fun record(event: VoucherPoolShutdownEvent) {
        eventLock.withLock { recordedEvents += event }
    }

    private fun updateReason(reason: VoucherPoolShutdownReason) {
        if (shutdownReason == VoucherPoolShutdownReason.CLEAN) {
            shutdownReason = reason
        }
    }

    private fun remaining(deadline: Long): Duration =
        ((deadline - System.nanoTime()).coerceAtLeast(0L)).nanos()

}

/** scheduled worker와 Task 8 claim path가 공유하는 rejection/cancellation state입니다. */
@Component
internal class VoucherPoolRuntimeControl {

    companion object: KLogging()

    private val acceptingCommands = AtomicBoolean(true)
    private val acceptingClaims = AtomicBoolean(true)
    private val triggersRunning = AtomicBoolean(true)
    private val claimLock = ReentrantLock()
    private val claimsReleased = claimLock.newCondition()
    private var activeClaims = 0

    fun rejectCommandsAndSse() {
        acceptingCommands.set(false)
    }

    fun stopTriggers() {
        triggersRunning.set(false)
    }

    fun stopClaims() {
        claimLock.withLock {
            acceptingClaims.set(false)
            if (activeClaims == 0) claimsReleased.signalAll()
        }
    }

    fun commandsAccepted(): Boolean = acceptingCommands.get()

    fun claimsAccepted(): Boolean = acceptingClaims.get()

    fun triggersRunning(): Boolean = triggersRunning.get()

    fun <T> withClaim(block: () -> T): T? {
        val reserved = claimLock.withLock {
            if (!acceptingClaims.get()) {
                false
            } else {
                activeClaims++
                true
            }
        }
        if (!reserved) return null

        return try {
            block()
        } finally {
            claimLock.withLock {
                activeClaims--
                check(activeClaims >= 0) { "worker claim accounting underflow" }
                if (activeClaims == 0) claimsReleased.signalAll()
            }
        }
    }

    fun awaitClaimRelease(timeout: Duration): Boolean {
        timeout.requireZeroOrPositiveDuration("timeout")

        var remaining = timeout.toNanos()
        claimLock.withLock {
            while (activeClaims > 0 && remaining > 0L) {
                remaining = claimsReleased.awaitNanos(remaining)
            }
            return activeClaims == 0
        }
    }

}

/** Spring이 infrastructure bean을 destroy하기 전에 application-owned shutdown을 실행합니다. */
@Component
@Suppress("LongParameterList")
internal class VoucherPoolLifecycle(
    applicationContext: ApplicationContext,
    gate: DatabasePermitGate,
    runtime: VoucherPoolRuntimeControl,
    health: VoucherPoolHealthState,
    streamShutdown: ObjectProvider<VoucherPoolStreamShutdown>,
    redis: ObjectProvider<VoucherPoolRedisResources>,
    @Qualifier("voucherPoolExecutor") executor: ObjectProvider<ExecutorService>,
    dataSource: DataSource,
): SmartLifecycle {
    private val running = AtomicBoolean()

    @Suppress("TooManyFunctions")
    private val coordinator = VoucherPoolLifecycleCoordinator(
        actions = object: VoucherPoolLifecycleActions {
            override fun readinessDown() {
                health.fail(VoucherPoolHealthComponent.LIFECYCLE, VoucherPoolHealthReason.SHUTTING_DOWN)
                AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)
            }

            override fun rejectCommandsAndSse() {
                runtime.rejectCommandsAndSse()
                gate.beginShutdown()
            }

            override fun stopTriggers() = runtime.stopTriggers()

            override fun stopClaims() = runtime.stopClaims()

            override fun awaitTransactions(timeout: Duration): Boolean = gate.awaitDrained(timeout)

            override fun cancelTransactions() {
                gate.cancelActiveTransactions()
            }

            override fun verifyRollback(): Boolean =
                gate.awaitDrained(ROLLBACK_VERIFY_TIMEOUT_SECONDS.seconds())

            override fun awaitClaimRelease(timeout: Duration): Boolean = runtime.awaitClaimRelease(timeout)

            override fun closeSseAndPoller() {
                streamShutdown.ifAvailable?.closeSseAndPoller()
            }

            override fun closeAdvisoryResources() {
                redis.ifAvailable?.close()
            }

            override fun closeExecutor() {
                executor.ifAvailable?.let { service ->
                    service.shutdown()
                    if (!service.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_SECONDS.seconds)) {
                        service.shutdownNow()
                    }
                }
            }

            override fun closeDataSource() {
                when (dataSource) {
                    is HikariDataSource -> dataSource.close()
                    is AutoCloseable    -> dataSource.close()
                }
            }
        },
    )

    override fun start() {
        running.set(true)
    }

    override fun stop(callback: Runnable) {
        if (running.compareAndSet(true, false)) {
            coordinator.shutdown()
        }
        callback.run()
    }

    override fun stop() = stop {}

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - LIFECYCLE_PHASE_OFFSET

    internal fun events(): List<VoucherPoolShutdownEvent> = coordinator.events()

    internal fun reason(): VoucherPoolShutdownReason = coordinator.reason()

    private companion object: KLogging() {
        const val EXECUTOR_CLOSE_TIMEOUT_SECONDS = 5L
        const val LIFECYCLE_PHASE_OFFSET = 100
        const val ROLLBACK_VERIFY_TIMEOUT_SECONDS = 5L
    }
}
