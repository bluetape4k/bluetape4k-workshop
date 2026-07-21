package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.workshop.commerce.ticket.operations.internal.OperationsService
import io.bluetape4k.workshop.commerce.ticket.operations.internal.ReconciliationJob
import io.bluetape4k.workshop.commerce.ticket.web.TicketEventStream
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import java.io.Serial
import java.io.Serializable
import java.time.Duration
import javax.sql.DataSource

/** Closed configuration contract for the concert ticket example. */
@ConfigurationProperties(prefix = "workshop.ticket", ignoreUnknownFields = false)
data class TicketProperties(
    val db: TicketDatabaseProperties = TicketDatabaseProperties(),
    val redis: TicketRedisProperties = TicketRedisProperties(),
    val worker: TicketWorkerProperties = TicketWorkerProperties(),
    val sse: TicketSseProperties = TicketSseProperties(),
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Database pool and workload-lane capacity. */
data class TicketDatabaseProperties(
    val maxPoolSize: Int = 20,
    val foregroundPermits: Int = 12,
    val workerPermits: Int = 3,
    val ssePermits: Int = 2,
    val operatorPermits: Int = 1,
    val permitTimeout: Duration = Duration.ofMillis(250),
    val transactionTimeout: Duration = Duration.ofMillis(750),
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Redis admission lease timing. */
data class TicketRedisProperties(
    val uri: String = "redis://localhost:6379",
    val commandTimeout: Duration = Duration.ofMillis(500),
    val renewInterval: Duration = Duration.ofSeconds(2),
    val leaseTtl: Duration = Duration.ofSeconds(5),
    val rateLimitCapacity: Long = 30,
    val rateLimitPeriod: Duration = Duration.ofSeconds(1),
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Background worker bounds. */
data class TicketWorkerProperties(
    val batchSize: Int = 50,
    val runDeadline: Duration = Duration.ofSeconds(10),
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Server-sent event capacity. */
data class TicketSseProperties(
    val queueSize: Int = 32,
    val maxConnections: Int = 512,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Stable startup failure codes safe to expose in diagnostics. */
enum class TicketStartupFailure {
    INVALID_DATABASE_CAPACITY,
    INVALID_DATABASE_TIMING,
    INVALID_REDIS_CONFIGURATION,
    INVALID_REDIS_LEASE_TIMING,
    INVALID_WORKER_CAPACITY,
    INVALID_SSE_CAPACITY,
}

/** Fails startup without leaking raw configuration values. */
class TicketStartupException(
    val code: TicketStartupFailure,
) : IllegalStateException(code.name) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Validates cross-field capacity and timing invariants. */
object TicketStartupValidator {
    private const val RESERVED_DATABASE_CONNECTIONS = 2

    fun validate(properties: TicketProperties) {
        validateDatabase(properties.db)
        validateRedis(properties.redis)
        validateWorker(properties.worker)
        validateSse(properties.sse)
    }

    private fun validateDatabase(db: TicketDatabaseProperties) {
        val lanes = listOf(db.foregroundPermits, db.workerPermits, db.ssePermits, db.operatorPermits)
        if (db.maxPoolSize <= RESERVED_DATABASE_CONNECTIONS || lanes.any { it <= 0 } ||
            lanes.sum() > db.maxPoolSize - RESERVED_DATABASE_CONNECTIONS
        ) {
            throw TicketStartupException(TicketStartupFailure.INVALID_DATABASE_CAPACITY)
        }
        if (!db.permitTimeout.isPositive() || !db.transactionTimeout.isPositive()) {
            throw TicketStartupException(TicketStartupFailure.INVALID_DATABASE_TIMING)
        }
    }

    private fun validateRedis(redis: TicketRedisProperties) {
        if (redis.uri.isBlank() || redis.rateLimitCapacity <= 0 || !redis.rateLimitPeriod.isPositive()) {
            throw TicketStartupException(TicketStartupFailure.INVALID_REDIS_CONFIGURATION)
        }
        if (!redis.commandTimeout.isPositive() || !redis.renewInterval.isPositive() || !redis.leaseTtl.isPositive() ||
            redis.commandTimeout >= redis.renewInterval ||
            redis.renewInterval.multipliedBy(2) >= redis.leaseTtl
        ) {
            throw TicketStartupException(TicketStartupFailure.INVALID_REDIS_LEASE_TIMING)
        }
    }

    private fun validateWorker(worker: TicketWorkerProperties) {
        if (worker.batchSize <= 0 || !worker.runDeadline.isPositive()) {
            throw TicketStartupException(TicketStartupFailure.INVALID_WORKER_CAPACITY)
        }
    }

    private fun validateSse(sse: TicketSseProperties) {
        if (sse.queueSize <= 0 || sse.maxConnections <= 0) {
            throw TicketStartupException(TicketStartupFailure.INVALID_SSE_CAPACITY)
        }
    }
}

/** Installs the startup validation gate. */
@Configuration(proxyBeanMethods = false)
internal class TicketConfiguration {
    @Bean
    fun ticketStartupValidation(properties: TicketProperties): SmartInitializingSingleton =
        SmartInitializingSingleton { TicketStartupValidator.validate(properties) }

    @Bean
    fun ticketMigrationReadiness(): TicketMigrationReadiness = TicketMigrationReadiness()

    @Bean
    fun ticketMigrationRunner(
        dataSource: DataSource,
        readiness: TicketMigrationReadiness,
    ): TicketMigrationRunner =
        TicketMigrationRunner(
            dataSource = dataSource,
            migration =
                TicketMigration(
                    version = "001",
                    resource = ClassPathResource("db/migration/V001__concert_ticket_flash_sale.sql"),
                ),
            advisoryLockKey = TICKET_MIGRATION_LOCK_KEY,
            readiness = readiness,
        )

    @Bean
    fun ticketSchemaMigration(runner: TicketMigrationRunner): SmartInitializingSingleton =
        SmartInitializingSingleton { runner.migrate() }

    @Bean
    fun ticketEventStream(properties: TicketProperties): TicketEventStream =
        TicketEventStream(properties.sse.queueSize, properties.sse.maxConnections)

    @Bean
    fun ticketLifecycle(eventStream: TicketEventStream): TicketLifecycle = TicketLifecycle(eventStream)

    @Bean
    fun ticketMetrics(registry: MeterRegistry): TicketMetrics = TicketMetrics(registry)

    @Bean
    fun ticketMigrationHealth(readiness: TicketMigrationReadiness): TicketMigrationHealthIndicator =
        TicketMigrationHealthIndicator(readiness)

    @Bean
    fun ticketRedisHealth(resources: TicketRedisResources): TicketRedisHealthIndicator =
        TicketRedisHealthIndicator(TicketRedisHealthProbe { resources.commands().ping() })

    @Bean
    fun ticketLivenessHealth(): TicketLivenessHealthIndicator = TicketLivenessHealthIndicator()

    @Bean
    fun ticketOperations(
        jobs: ObjectProvider<ReconciliationJob>,
        properties: TicketProperties,
    ): OperationsService = OperationsService(
        jobs = jobs.orderedStream().toList(),
        operatorPermits = properties.db.operatorPermits,
        maxBatchSize = properties.worker.batchSize,
        runDeadline = properties.worker.runDeadline,
    )

    companion object {
        private const val TICKET_MIGRATION_LOCK_KEY: Long = 521_001L
    }
}
