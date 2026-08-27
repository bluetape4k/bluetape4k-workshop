package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.codec.Base58
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
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolHttpProperties
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.ExecutorService
import javax.sql.DataSource
import kotlin.time.toKotlinDuration

private const val DEFAULT_FOREGROUND_CAPACITY = 11
private const val DEFAULT_FOREGROUND_PERMIT_WAIT_MILLIS = 200L
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
private const val DEFAULT_SSE_MAX_SUBSCRIBERS = 32
private const val DEFAULT_SSE_MAX_SUBSCRIBERS_PER_SCOPE = 8
private const val DEFAULT_SSE_QUEUE_SIZE = 64
private const val DEFAULT_SSE_MAX_QUEUE_BYTES = 256 * 1024
private const val DEFAULT_SSE_MAX_POLL_ROWS = 100
private const val DEFAULT_SSE_MAX_POLL_BYTES = 512 * 1024
private const val DEFAULT_SSE_MAX_CUSTOMER_CAMPAIGNS = 64
private const val DEFAULT_SSE_POLL_INTERVAL_MILLIS = 250L
private const val DEFAULT_SSE_MAX_IDLE_INTERVAL_SECONDS = 2L
private const val DEFAULT_SSE_HEARTBEAT_INTERVAL_SECONDS = 15L
private const val DEFAULT_SSE_WRITE_TIMEOUT_SECONDS = 5L
private const val DEFAULT_WORKER_INSTANCE_SUFFIX_LENGTH = 16

@ConfigurationProperties(prefix = "workshop.voucher-pool")
internal data class VoucherPoolProperties(
    val database: VoucherPoolDatabaseProperties = VoucherPoolDatabaseProperties(),
    val redis: VoucherPoolRedisProperties = VoucherPoolRedisProperties(),
    val sse: VoucherPoolSseProperties = VoucherPoolSseProperties(),
    val http: VoucherPoolHttpProperties,
    val workerInstanceId: String = "voucher-pool-${Base58.randomString(DEFAULT_WORKER_INSTANCE_SUFFIX_LENGTH)}",
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class VoucherPoolSseProperties(
    val maxSubscribers: Int = DEFAULT_SSE_MAX_SUBSCRIBERS,
    val maxSubscribersPerScope: Int = DEFAULT_SSE_MAX_SUBSCRIBERS_PER_SCOPE,
    val queueSize: Int = DEFAULT_SSE_QUEUE_SIZE,
    val maxQueueBytes: Int = DEFAULT_SSE_MAX_QUEUE_BYTES,
    val maxPollRows: Int = DEFAULT_SSE_MAX_POLL_ROWS,
    val maxPollBytes: Int = DEFAULT_SSE_MAX_POLL_BYTES,
    val maxCustomerCampaigns: Int = DEFAULT_SSE_MAX_CUSTOMER_CAMPAIGNS,
    val pollInterval: Duration = Duration.ofMillis(DEFAULT_SSE_POLL_INTERVAL_MILLIS),
    val maxIdleInterval: Duration = Duration.ofSeconds(DEFAULT_SSE_MAX_IDLE_INTERVAL_SECONDS),
    val heartbeatInterval: Duration = Duration.ofSeconds(DEFAULT_SSE_HEARTBEAT_INTERVAL_SECONDS),
    val writeTimeout: Duration = Duration.ofSeconds(DEFAULT_SSE_WRITE_TIMEOUT_SECONDS),
) : Serializable {
    init {
        require(maxSubscribers > 0 && maxSubscribersPerScope in 1..maxSubscribers)
        require(queueSize > 1 && maxQueueBytes > 0 && maxPollRows > 0 && maxPollBytes > 0)
        require(maxCustomerCampaigns > 0)
        require(!pollInterval.isZero && !pollInterval.isNegative)
        require(maxIdleInterval >= pollInterval)
        require(!heartbeatInterval.isZero && !heartbeatInterval.isNegative)
        require(!writeTimeout.isZero && !writeTimeout.isNegative)
    }

    companion object { private const val serialVersionUID: Long = 1L }
}

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
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class VoucherPoolLaneProperties(
    val capacity: Int,
    val permitWait: Duration,
    val transactionTimeout: Duration,
    val lockTimeout: Duration,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

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
            sseTransaction = database.sse.transactionTimeout,
            foregroundLock = database.foreground.lockTimeout,
            operatorLock = database.foreground.lockTimeout,
            workerLock = database.worker.lockTimeout,
            sseLock = database.sse.lockTimeout,
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
