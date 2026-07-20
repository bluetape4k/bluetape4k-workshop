package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcMetrics
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolLockTimeoutApplier
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import javax.sql.DataSource

internal class VoucherPoolConfigurationTest {
    private val configuration = VoucherPoolConfiguration()

    @Test
    fun `live Hikari settings govern permit budget and acquisition timeout`() {
        HikariDataSource().use { dataSource ->
            dataSource.maximumPoolSize = 16
            dataSource.connectionTimeout = 4_321
            val properties = VoucherPoolProperties()

            val gate = configuration.databasePermitGate(dataSource, properties)
            val timeouts = configuration.voucherPoolJdbcTimeouts(dataSource, properties)
            val executor =
                configuration.voucherPoolJdbcExecutor(
                    gate = gate,
                    transactionManager = mockk<PlatformTransactionManager>(),
                    metrics = VoucherPoolJdbcMetrics.NONE,
                    lockTimeoutApplier = VoucherPoolLockTimeoutApplier.NONE,
                    timeouts = timeouts,
                )

            gate.snapshot().capacities.values.sum() shouldBeEqualTo dataSource.maximumPoolSize
            executor.timeouts.connectionAcquisition shouldBeEqualTo Duration.ofMillis(4_321)
        }
    }

    @Test
    fun `live Hikari pool size rejects lane capacity overcommit`() {
        HikariDataSource().use { dataSource ->
            dataSource.maximumPoolSize = 15

            assertFailsWith<IllegalArgumentException> {
                configuration.databasePermitGate(dataSource, VoucherPoolProperties())
            }
        }
    }

    @Test
    fun `non-Hikari data source fails configuration without opening a connection`() {
        val dataSource = mockk<DataSource>(relaxed = true)

        val failure =
            assertFailsWith<IllegalStateException> {
                configuration.databasePermitGate(dataSource, VoucherPoolProperties())
            }

        failure.message shouldBeEqualTo
            "pre-generated-voucher-pool requires HikariDataSource so the live pool size and connection timeout " +
            "remain authoritative"
    }
}
