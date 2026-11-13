package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcMetrics
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolLockTimeoutApplier
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolHttpProperties
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

internal class VoucherPoolConfigurationTest {

    companion object: KLogging()

    private val configuration = VoucherPoolConfiguration()

    @Test
    fun `live Hikari settings govern permit budget and acquisition timeout`() {
        HikariDataSource().use { dataSource ->
            dataSource.maximumPoolSize = 16
            dataSource.connectionTimeout = 4_321
            val properties = testProperties()

            val gate = configuration.databasePermitGate(dataSource, properties)
            val timeouts = configuration.voucherPoolJdbcTimeouts(dataSource, properties)
            val executor = configuration.voucherPoolJdbcExecutor(
                gate = gate,
                transactionManager = mockk<PlatformTransactionManager>(),
                metrics = VoucherPoolJdbcMetrics.NONE,
                lockTimeoutApplier = VoucherPoolLockTimeoutApplier.NONE,
                timeouts = timeouts,
            )

            log.debug { "DatabasePermitGate: ${gate.snapshot()}" }
            gate.snapshot().capacities.values.sum() shouldBeEqualTo dataSource.maximumPoolSize - 1
            executor.timeouts.connectionAcquisition shouldBeEqualTo 4_321.millis()
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
            val properties = VoucherPoolProperties(
                http = testHttpProperties(),
                database = VoucherPoolDatabaseProperties(
                    sse = VoucherPoolLaneProperties(
                        capacity = 3,
                        permitWait = 1.seconds(),
                        transactionTimeout = 7.seconds(),
                        lockTimeout = 3.seconds(),
                    ),
                ),
            )

            val timeouts = configuration.voucherPoolJdbcTimeouts(dataSource, properties)
            log.debug { "timeouts: $timeouts" }
            timeouts.sseTransaction shouldBeEqualTo 7.seconds()
            timeouts.sseLock shouldBeEqualTo 3.seconds()
        }
    }

    @Test
    fun `configuration representations redact operator credentials`() {
        val properties = testProperties()

        log.debug { "properties: $properties" }
        properties.http.toString().let {
            it shouldNotContain "test-operator-secret"
            it shouldNotContain "test-voucher-pool-operator-guard"
        }

        properties.toString().let {
            it shouldNotContain "test-operator-secret"
            it shouldNotContain "test-voucher-pool-operator-guard"
        }
    }

    @Test
    fun `default worker instance uses a compact base58 namespace`() {
        val workerInstanceId = testProperties().workerInstanceId
        val suffix = workerInstanceId.removePrefix("voucher-pool-")

        workerInstanceId shouldStartWith "voucher-pool-"
        suffix.length shouldBeEqualTo 16
        suffix.all { it.isLetterOrDigit() }.shouldBeTrue()
    }

    @Test
    fun `non-Hikari data source fails configuration without opening a connection`() {
        val dataSource = mockk<DataSource>(relaxed = true)

        val failure = assertFailsWith<IllegalStateException> {
            configuration.databasePermitGate(dataSource, testProperties())
        }

        log.debug { "failure: $failure" }
        failure.message shouldBeEqualTo
                "pre-generated-voucher-pool requires HikariDataSource so the live pool size and connection timeout " +
                "remain authoritative"
    }

    private fun testProperties(): VoucherPoolProperties =
        VoucherPoolProperties(http = testHttpProperties())

    private fun testHttpProperties(): VoucherPoolHttpProperties =
        VoucherPoolHttpProperties(
            operatorSecret = "test-operator-secret-0000000000000001",
            operatorGuard = "test-voucher-pool-operator-guard",
        )
}
