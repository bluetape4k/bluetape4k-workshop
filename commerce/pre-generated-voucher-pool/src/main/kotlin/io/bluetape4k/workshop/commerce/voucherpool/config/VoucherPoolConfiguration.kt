package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.admission.PermitLane
import io.bluetape4k.workshop.commerce.voucherpool.admission.PermitLaneConfig
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcMetrics
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeouts
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolLockTimeoutApplier
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import javax.sql.DataSource
import kotlin.time.toKotlinDuration

private const val DEFAULT_FOREGROUND_CAPACITY = 12
private const val DEFAULT_FOREGROUND_PERMIT_WAIT_MILLIS = 250L
private const val DEFAULT_FOREGROUND_TRANSACTION_TIMEOUT_SECONDS = 5L
private const val DEFAULT_FOREGROUND_LOCK_TIMEOUT_SECONDS = 5L
private const val DEFAULT_WORKER_CAPACITY = 1
private const val DEFAULT_WORKER_PERMIT_WAIT_SECONDS = 1L
private const val DEFAULT_WORKER_TRANSACTION_TIMEOUT_SECONDS = 10L
private const val DEFAULT_WORKER_LOCK_TIMEOUT_SECONDS = 10L
private const val DEFAULT_SSE_CAPACITY = 3
private const val DEFAULT_SSE_PERMIT_WAIT_SECONDS = 1L
private const val DEFAULT_SSE_TRANSACTION_TIMEOUT_SECONDS = 5L
private const val DEFAULT_SSE_LOCK_TIMEOUT_SECONDS = 5L

@ConfigurationProperties(prefix = "workshop.voucher-pool")
internal data class VoucherPoolProperties(
    val database: VoucherPoolDatabaseProperties = VoucherPoolDatabaseProperties(),
    val redis: VoucherPoolRedisProperties = VoucherPoolRedisProperties(),
    val workerInstanceId: String = "voucher-pool-${UUID.randomUUID()}",
)

internal data class VoucherPoolDatabaseProperties(
    val foreground: VoucherPoolLaneProperties =
        VoucherPoolLaneProperties(
            capacity = DEFAULT_FOREGROUND_CAPACITY,
            permitWait = Duration.ofMillis(DEFAULT_FOREGROUND_PERMIT_WAIT_MILLIS),
            transactionTimeout = Duration.ofSeconds(DEFAULT_FOREGROUND_TRANSACTION_TIMEOUT_SECONDS),
            lockTimeout = Duration.ofSeconds(DEFAULT_FOREGROUND_LOCK_TIMEOUT_SECONDS),
        ),
    val worker: VoucherPoolLaneProperties =
        VoucherPoolLaneProperties(
            capacity = DEFAULT_WORKER_CAPACITY,
            permitWait = Duration.ofSeconds(DEFAULT_WORKER_PERMIT_WAIT_SECONDS),
            transactionTimeout = Duration.ofSeconds(DEFAULT_WORKER_TRANSACTION_TIMEOUT_SECONDS),
            lockTimeout = Duration.ofSeconds(DEFAULT_WORKER_LOCK_TIMEOUT_SECONDS),
        ),
    val sse: VoucherPoolLaneProperties =
        VoucherPoolLaneProperties(
            capacity = DEFAULT_SSE_CAPACITY,
            permitWait = Duration.ofSeconds(DEFAULT_SSE_PERMIT_WAIT_SECONDS),
            transactionTimeout = Duration.ofSeconds(DEFAULT_SSE_TRANSACTION_TIMEOUT_SECONDS),
            lockTimeout = Duration.ofSeconds(DEFAULT_SSE_LOCK_TIMEOUT_SECONDS),
        ),
)

internal data class VoucherPoolLaneProperties(
    val capacity: Int,
    val permitWait: Duration,
    val transactionTimeout: Duration,
    val lockTimeout: Duration,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherPoolProperties::class)
internal class VoucherPoolConfiguration {
    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean(destroyMethod = "close")
    fun voucherPoolExecutor(): ExecutorService =
        VirtualThreads.executorService().also {
            log.info { "voucher_pool_executor_created virtualThreads=true" }
        }

    @Bean
    fun databasePermitGate(
        dataSource: DataSource,
        properties: VoucherPoolProperties,
    ): DatabasePermitGate {
        val database = properties.database
        return DatabasePermitGate(
            hikariMaximumPoolSize = dataSource.requireHikariDataSource().maximumPoolSize,
            configs =
                mapOf(
                    PermitLane.FOREGROUND to database.foreground.toPermitConfig(),
                    PermitLane.WORKER to database.worker.toPermitConfig(),
                    PermitLane.SSE to database.sse.toPermitConfig(),
                ),
        )
    }

    @Bean
    fun voucherPoolLockTimeoutApplier(): VoucherPoolLockTimeoutApplier =
        VoucherPoolLockTimeoutApplier { timeout ->
            TransactionManager.current().exec("SET LOCAL lock_timeout = '${timeout.toMillis()}ms'")
        }

    @Bean
    fun voucherPoolJdbcTimeouts(
        dataSource: DataSource,
        properties: VoucherPoolProperties,
    ): VoucherPoolJdbcTimeouts {
        val database = properties.database
        val hikariDataSource = dataSource.requireHikariDataSource()
        return VoucherPoolJdbcTimeouts(
            connectionAcquisition = Duration.ofMillis(hikariDataSource.connectionTimeout),
            foregroundTransaction = database.foreground.transactionTimeout,
            operatorTransaction = database.foreground.transactionTimeout,
            workerChunkTransaction = database.worker.transactionTimeout,
            foregroundLock = database.foreground.lockTimeout,
            operatorLock = database.foreground.lockTimeout,
            workerLock = database.worker.lockTimeout,
        )
    }

    @Bean
    fun voucherPoolJdbcExecutor(
        gate: DatabasePermitGate,
        @Qualifier("springTransactionManager") transactionManager: PlatformTransactionManager,
        metrics: VoucherPoolJdbcMetrics,
        lockTimeoutApplier: VoucherPoolLockTimeoutApplier,
        timeouts: VoucherPoolJdbcTimeouts,
    ): VoucherPoolJdbcExecutor =
        VoucherPoolJdbcExecutor(
            gate = gate,
            transactionManager = transactionManager,
            timeouts = timeouts,
            metrics = metrics,
            lockTimeoutApplier = lockTimeoutApplier,
        )

    private fun VoucherPoolLaneProperties.toPermitConfig(): PermitLaneConfig =
        PermitLaneConfig(
            capacity = capacity,
            wait = permitWait.toKotlinDuration(),
        )

    private fun DataSource.requireHikariDataSource(): HikariDataSource =
        this as? HikariDataSource
            ?: error(
                "pre-generated-voucher-pool requires HikariDataSource so the live pool size and connection timeout " +
                    "remain authoritative",
            )

    companion object : KLogging()
}
