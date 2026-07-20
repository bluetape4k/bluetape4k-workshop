package io.bluetape4k.workshop.commerce.voucherpool

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcExecutionLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcTimeoutPhase
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcMetrics
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeouts
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal class RuntimeContractTest {
    @Test
    fun `runtime uses Java 25 virtual threads and excludes the JDK21 provider`() {
        Runtime.version().feature() shouldBeEqualTo 25
        VirtualThreads.runtimeName() shouldBeEqualTo "jdk25"
        VirtualThreads.executorService().use { executor ->
            executor.submit<Boolean> { Thread.currentThread().isVirtual }.get().shouldBeTrue()
        }

        val properties = applicationProperties()
        properties.getProperty("spring.threads.virtual.enabled") shouldBeEqualTo "true"
        properties.getProperty("spring.datasource.hikari.maximum-pool-size") shouldBeEqualTo "16"
        properties.getProperty("spring.datasource.hikari.connection-timeout") shouldBeEqualTo "2000"
        properties.getProperty("workshop.voucher-pool.database.foreground.capacity") shouldBeEqualTo "12"
        properties.getProperty("workshop.voucher-pool.database.foreground.permit-wait") shouldBeEqualTo "250ms"
        properties.getProperty("workshop.voucher-pool.database.foreground.transaction-timeout") shouldBeEqualTo "5s"
        properties.getProperty("workshop.voucher-pool.database.foreground.lock-timeout") shouldBeEqualTo "5s"
        properties.getProperty("workshop.voucher-pool.database.worker.capacity") shouldBeEqualTo "1"
        properties.getProperty("workshop.voucher-pool.database.worker.permit-wait") shouldBeEqualTo "1s"
        properties.getProperty("workshop.voucher-pool.database.worker.transaction-timeout") shouldBeEqualTo "10s"
        properties.getProperty("workshop.voucher-pool.database.worker.lock-timeout") shouldBeEqualTo "10s"
        properties.getProperty("workshop.voucher-pool.database.sse.capacity") shouldBeEqualTo "3"
        properties.getProperty("workshop.voucher-pool.database.sse.permit-wait") shouldBeEqualTo "1s"
    }

    @Test
    fun `JDBC executor activates synchronization and signals after commit`() {
        val committed = AtomicBoolean()
        val manager = RecordingTransactionManager()
        val executor = VoucherPoolJdbcExecutor(DatabasePermitGate.default(16), manager)

        executor.foregroundTransaction {
            TransactionSynchronizationManager.isSynchronizationActive().shouldBeTrue()
            executor.afterCommit { committed.set(true) }
            committed.get().shouldBeFalse()
        }

        committed.get().shouldBeTrue()
        manager.commits shouldBeEqualTo 1
        manager.rollbacks shouldBeEqualTo 0
    }

    @Test
    fun `JDBC executor never signals after commit hook on rollback`() {
        val committed = AtomicBoolean()
        val manager = RecordingTransactionManager()
        val executor = VoucherPoolJdbcExecutor(DatabasePermitGate.default(16), manager)

        assertFailsWith<IllegalStateException> {
            executor.foregroundTransaction {
                TransactionSynchronizationManager.isSynchronizationActive().shouldBeTrue()
                executor.afterCommit { committed.set(true) }
                error("rollback")
            }
        }

        committed.get().shouldBeFalse()
        manager.commits shouldBeEqualTo 0
        manager.rollbacks shouldBeEqualTo 1
    }

    @Test
    fun `JDBC executor owns independent acquisition lock and transaction budgets`() {
        val executor = VoucherPoolJdbcExecutor(DatabasePermitGate.default(16), RecordingTransactionManager())

        executor.timeouts shouldBeEqualTo
            VoucherPoolJdbcTimeouts(
                connectionAcquisition = Duration.ofSeconds(2),
                foregroundTransaction = Duration.ofSeconds(5),
                operatorTransaction = Duration.ofSeconds(5),
                workerChunkTransaction = Duration.ofSeconds(10),
                foregroundLock = Duration.ofSeconds(5),
                operatorLock = Duration.ofSeconds(5),
            )
    }

    @Test
    fun `JDBC executor maps timeout phases and records low cardinality metrics`() {
        val metrics = RecordingJdbcMetrics()
        val executor =
            VoucherPoolJdbcExecutor(
                gate = DatabasePermitGate.default(16),
                transactionManager = RecordingTransactionManager(),
                metrics = metrics,
            )

        val acquisition =
            assertFailsWith<VoucherPoolJdbcTimeoutException> {
                executor.foregroundTransaction<Unit> { failConnectionAcquisition() }
            }
        acquisition.phase shouldBeEqualTo JdbcTimeoutPhase.ACQUISITION

        val lock =
            assertFailsWith<VoucherPoolJdbcTimeoutException> {
                executor.operatorTransaction<Unit> { failLockAcquisition() }
            }
        lock.phase shouldBeEqualTo JdbcTimeoutPhase.LOCK

        val transaction =
            assertFailsWith<VoucherPoolJdbcTimeoutException> {
                executor.workerTransaction<Unit> { failTransactionDeadline() }
            }
        transaction.phase shouldBeEqualTo JdbcTimeoutPhase.TRANSACTION

        metrics.timeouts shouldBeEqualTo
            listOf(
                JdbcExecutionLane.FOREGROUND to JdbcTimeoutPhase.ACQUISITION,
                JdbcExecutionLane.OPERATOR to JdbcTimeoutPhase.LOCK,
                JdbcExecutionLane.WORKER to JdbcTimeoutPhase.TRANSACTION,
            )
    }

    private fun applicationProperties(): PropertySourcesPropertyResolver {
        val sources =
            MutablePropertySources().apply {
                YamlPropertySourceLoader()
                    .load("voucher-pool", ClassPathResource("application.yml"))
                    .forEach(::addLast)
            }
        return PropertySourcesPropertyResolver(sources)
    }

    private fun failConnectionAcquisition(): Nothing = throw SQLTransientConnectionException("acquisition")

    private fun failLockAcquisition(): Nothing = throw SQLException("lock", "55P03")

    private fun failTransactionDeadline(): Nothing = throw TransactionTimedOutException("deadline")

    private class RecordingTransactionManager : AbstractPlatformTransactionManager() {
        var commits: Int = 0
        var rollbacks: Int = 0

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(
            transaction: Any,
            definition: TransactionDefinition,
        ) = Unit

        override fun doCommit(status: DefaultTransactionStatus) {
            commits++
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            rollbacks++
        }
    }

    private class RecordingJdbcMetrics : VoucherPoolJdbcMetrics {
        val timeouts = CopyOnWriteArrayList<Pair<JdbcExecutionLane, JdbcTimeoutPhase>>()

        override fun timedOut(
            lane: JdbcExecutionLane,
            phase: JdbcTimeoutPhase,
        ) {
            timeouts += lane to phase
        }
    }
}
