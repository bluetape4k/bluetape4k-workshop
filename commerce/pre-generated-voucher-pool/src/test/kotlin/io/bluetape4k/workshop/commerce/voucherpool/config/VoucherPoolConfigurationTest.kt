package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcMetrics
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolLockTimeoutApplier
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolHttpProperties
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
            val properties = testProperties()

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

            gate.snapshot().capacities.values.sum() shouldBeEqualTo dataSource.maximumPoolSize - 1
            executor.timeouts.connectionAcquisition shouldBeEqualTo Duration.ofMillis(4_321)
        }
    }

    @Test
    fun `live Hikari pool size rejects lane capacity overcommit`() {
        HikariDataSource().use { dataSource ->
            dataSource.maximumPoolSize = 15

            assertFailsWith<IllegalArgumentException> {
                configuration.databasePermitGate(dataSource, testProperties())
            }
        }
    }

    @Test
    fun `SSE database properties map to JDBC transaction and lock timeouts`() {
        HikariDataSource().use { dataSource ->
            dataSource.maximumPoolSize = 16
            val properties =
                VoucherPoolProperties(
                    http = testHttpProperties(),
                    database =
                        VoucherPoolDatabaseProperties(
                            sse =
                                VoucherPoolLaneProperties(
                                    capacity = 3,
                                    permitWait = Duration.ofSeconds(1),
                                    transactionTimeout = Duration.ofSeconds(7),
                                    lockTimeout = Duration.ofSeconds(3),
                                ),
                        ),
                )

            val timeouts = configuration.voucherPoolJdbcTimeouts(dataSource, properties)

            timeouts.sseTransaction shouldBeEqualTo Duration.ofSeconds(7)
            timeouts.sseLock shouldBeEqualTo Duration.ofSeconds(3)
        }
    }

    @Test
    fun `configuration representations redact operator credentials`() {
        val properties = testProperties()

        properties.http.toString() shouldNotContain "test-operator-secret"
        properties.http.toString() shouldNotContain "test-voucher-pool-operator-guard"
        properties.toString() shouldNotContain "test-operator-secret"
        properties.toString() shouldNotContain "test-voucher-pool-operator-guard"
    }

    @Test
    fun `non-Hikari data source fails configuration without opening a connection`() {
        val dataSource = mockk<DataSource>(relaxed = true)

        val failure =
            assertFailsWith<IllegalStateException> {
                configuration.databasePermitGate(dataSource, testProperties())
            }

        failure.message shouldBeEqualTo
            "pre-generated-voucher-pool requires HikariDataSource so the live pool size and connection timeout " +
            "remain authoritative"
    }

    private fun testProperties(): VoucherPoolProperties = VoucherPoolProperties(http = testHttpProperties())

    private fun testHttpProperties(): VoucherPoolHttpProperties =
        VoucherPoolHttpProperties(
            operatorSecret = "test-operator-secret-0000000000000001",
            operatorGuard = "test-voucher-pool-operator-guard",
        )
}
