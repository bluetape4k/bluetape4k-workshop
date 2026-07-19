package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.config.VoucherCompatibilityTestSupport
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMigrationResult
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

internal class VoucherJdbcExecutorPermitTest : VoucherCompatibilityTestSupport() {
    private var exposedDatabase: Database? = null

    @BeforeEach
    fun migrate() {
        migrationRunner().migrate() shouldBeEqualTo VoucherMigrationResult.APPLIED
    }

    @Test
    fun `connection opens after permit and closes before the permit is returned`() {
        val gate =
            DatabasePermitGate(
                foregroundPermits = 1,
                workerPermits = 1,
                sseMaintenancePermits = 1,
                acquireTimeout = Duration.ofSeconds(1),
            )
        val probe = PermitCheckingDataSource(dataSource) { gate.requireHeld() }
        val manager = SpringTransactionManager(probe, DatabaseConfig {}, false)
        exposedDatabase = TransactionManager.primaryDatabase
        val jdbc = VoucherJdbcExecutor(gate, manager)

        probe.openCount.get() shouldBeEqualTo 0
        jdbc.foregroundTransaction {
            probe.openCount.get() shouldBeEqualTo 1
            probe.closeCount.get() shouldBeEqualTo 0
            gate.availablePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 0
        }

        probe.closeCount.get() shouldBeEqualTo 1
        gate.availablePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
    }

    @AfterEach
    fun closePermitDatabase() {
        exposedDatabase?.let(TransactionManager::closeAndUnregister)
    }

    private class PermitCheckingDataSource(
        private val delegate: DataSource,
        private val requirePermit: () -> Unit,
    ) : DataSource by delegate {
        val openCount = AtomicInteger()
        val closeCount = AtomicInteger()

        override fun getConnection(): Connection = checked(delegate.connection)

        override fun getConnection(
            username: String,
            password: String,
        ): Connection = checked(delegate.getConnection(username, password))

        private fun checked(connection: Connection): Connection {
            requirePermit()
            openCount.incrementAndGet()
            val closed = AtomicBoolean()
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, arguments ->
                if (method.name == "close" && closed.compareAndSet(false, true)) closeCount.incrementAndGet()
                method.invoke(connection, *(arguments ?: emptyArray()))
            } as Connection
        }
    }
}
