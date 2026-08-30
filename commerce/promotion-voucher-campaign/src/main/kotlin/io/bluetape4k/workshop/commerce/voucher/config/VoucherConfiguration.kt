package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeKeyRing
import io.bluetape4k.workshop.commerce.voucher.security.VoucherCodeService
import io.bluetape4k.workshop.commerce.voucher.web.VoucherHttpProperties
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.SpringProperties
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@ConfigurationProperties(prefix = "workshop.voucher", ignoreUnknownFields = false)
internal data class VoucherProperties(
    val db: VoucherDatabaseProperties = VoucherDatabaseProperties(),
    val keys: VoucherKeyProperties = VoucherKeyProperties(),
    val redis: VoucherRedisProperties = VoucherRedisProperties(),
    val worker: VoucherWorkerProperties = VoucherWorkerProperties(),
    val sse: VoucherSseProperties = VoucherSseProperties(),
    val retention: VoucherRetentionProperties = VoucherRetentionProperties(),
    val http: VoucherHttpProperties = VoucherHttpProperties(),
)

internal data class VoucherDatabaseProperties(
    val foregroundPermits: Int = 12,
    val backgroundPermits: Int = 4,
    val permitTimeout: Duration = Duration.ofMillis(250),
    val lockTimeout: Duration = Duration.ofSeconds(5),
)

internal data class VoucherKeyProperties(
    val currentVersion: Int = 1,
    val activeReadVersions: Set<Int> = setOf(1),
    val generation: Map<Int, String> = emptyMap(),
    val verification: Map<Int, String> = emptyMap(),
    val identity: String = "",
    val risk: String = "",
    val redisSlot: String = "",
)

internal data class VoucherRedisProperties(
    val enabled: Boolean = false,
    val uri: String = "redis://127.0.0.1:6379",
    val commandTimeout: Duration = Duration.ofMillis(500),
    val failureThreshold: Int = 3,
    val recoverySuccessThreshold: Int = 3,
    val probeInterval: Duration = Duration.ofSeconds(1),
    val maxInFlightProbes: Int = 1,
    val quotaCapacity: Long = 10,
    val quotaPeriod: Duration = Duration.ofMinutes(1),
    val bloomExpectedInsertions: Long = 100_000,
    val bloomFalseProbability: Double = 0.01,
)

internal data class VoucherWorkerProperties(
    val batchSize: Int = 50,
    val runDeadline: Duration = Duration.ofSeconds(10),
    val transactionTimeout: Duration = Duration.ofSeconds(5),
    val maxAttempts: Int = 5,
    val instanceId: String = "voucher-node",
    val schedulingEnabled: Boolean = true,
    val interval: Duration = Duration.ofSeconds(5),
    val initialDelay: Duration = Duration.ofSeconds(5),
)

internal data class VoucherSseProperties(
    val maxCampaigns: Int = 32,
    val queueSize: Int = 32,
    val pollInterval: Duration = Duration.ofMillis(500),
    val maxIdleInterval: Duration = Duration.ofSeconds(2),
    val heartbeatInterval: Duration = Duration.ofSeconds(15),
    val writeTimeout: Duration = Duration.ofSeconds(5),
    val maxRows: Int = 200,
    val maxPayloadBytes: Int = 256 * 1024,
)

internal data class VoucherRuntimeEnvironment(
    val activeProfiles: Set<String>,
    val serverAddress: String,
    val hikariMaximumPoolSize: Int,
)

internal data class ReferencedKeyVersions(
    val generation: Set<Int> = emptySet(),
    val verification: Set<Int> = emptySet(),
)

internal fun interface ReferencedKeyVersionSource {
    fun referencedVersions(): ReferencedKeyVersions

    companion object {
        val NONE: ReferencedKeyVersionSource = ReferencedKeyVersionSource { ReferencedKeyVersions() }
    }
}

internal enum class StartupFailureCode {
    UNKNOWN_PROPERTY,
    INVALID_RANGE,
    MISSING_KEY,
    WEAK_KEY,
    TEST_KEY_FORBIDDEN,
    INVALID_KEY_RING,
    DOMAIN_KEY_REUSE,
    REFERENCED_KEY_MISSING,
    PUBLIC_DEMO_BIND,
    UNEXPECTED_STARTUP_FAILURE,
}

internal class VoucherStartupException(
    val code: StartupFailureCode,
) : IllegalStateException(code.name)

internal fun sanitizedStartupCode(failure: Throwable): StartupFailureCode {
    var current: Throwable? = failure
    while (current != null) {
        if (current is VoucherStartupException) return current.code
        val message = current.message.orEmpty().lowercase()
        if ("left unbound" in message || "unbound configuration" in message) {
            return StartupFailureCode.UNKNOWN_PROPERTY
        }
        current = current.cause
    }
    return StartupFailureCode.UNEXPECTED_STARTUP_FAILURE
}

/** exception에 secret value를 노출하지 않고 fail-closed production check를 적용합니다. */
internal class VoucherStartupValidator(
    private val referencedKeyVersions: ReferencedKeyVersionSource = ReferencedKeyVersionSource.NONE,
) {
    fun validate(
        properties: VoucherProperties,
        runtime: VoucherRuntimeEnvironment,
    ) {
        validateDatabase(properties.db, runtime.hikariMaximumPoolSize)
        validateRedis(properties.redis)
        validateWorker(properties.worker)
        validateSse(properties.sse)
        validateRetention(properties.retention)
        validateHttp(properties.http)
        validateKeys(properties.keys, runtime.isProduction())
        validateReferencedKeys(properties.keys)
        validateBind(runtime)
    }

    private fun validateDatabase(
        database: VoucherDatabaseProperties,
        hikariMaximumPoolSize: Int,
    ) {
        val invalid =
            hikariMaximumPoolSize != HIKARI_MAXIMUM_POOL_SIZE ||
                database.foregroundPermits <= 0 ||
                database.backgroundPermits != BACKGROUND_PERMITS ||
                database.foregroundPermits + database.backgroundPermits > hikariMaximumPoolSize ||
                database.permitTimeout.isNegative ||
                database.permitTimeout.isZero ||
                database.lockTimeout.isNegative ||
                database.lockTimeout.isZero
        if (invalid) fail(StartupFailureCode.INVALID_RANGE)
    }

    private fun validateKeys(
        keys: VoucherKeyProperties,
        production: Boolean,
    ) {
        val currentGeneration = keys.generation[keys.currentVersion]
        val currentVerification = keys.verification[keys.currentVersion]
        val required = listOf(currentGeneration, currentVerification, keys.identity, keys.risk, keys.redisSlot)
        if (required.any { it.isNullOrBlank() }) fail(StartupFailureCode.MISSING_KEY)

        val nonNullRequired = required.filterNotNull()
        if (nonNullRequired.any { it.toByteArray(StandardCharsets.UTF_8).size < MINIMUM_KEY_BYTES }) {
            fail(StartupFailureCode.WEAK_KEY)
        }
        if (production && nonNullRequired.any(::isKnownTestKey)) {
            fail(StartupFailureCode.TEST_KEY_FORBIDDEN)
        }
        val ringInvalid =
            keys.currentVersion !in keys.activeReadVersions ||
                keys.activeReadVersions.any { it !in keys.generation || it !in keys.verification }
        if (ringInvalid) fail(StartupFailureCode.INVALID_KEY_RING)

        val domains =
            buildList {
                keys.generation.values.forEach { add("generation" to it) }
                keys.verification.values.forEach { add("verification" to it) }
                add("identity" to keys.identity)
                add("risk" to keys.risk)
                add("redis-slot" to keys.redisSlot)
            }
        val reusedAcrossDomains =
            domains
                .groupBy { it.second }
                .values
                .any { values -> values.map { it.first }.distinct().size > 1 }
        if (reusedAcrossDomains) fail(StartupFailureCode.DOMAIN_KEY_REUSE)
    }

    private fun validateRedis(redis: VoucherRedisProperties) {
        val invalid =
            (redis.enabled && redis.uri.isBlank()) ||
                redis.commandTimeout.isNegative ||
                redis.commandTimeout.isZero ||
                redis.failureThreshold <= 0 ||
                redis.recoverySuccessThreshold <= 0 ||
                redis.probeInterval.isNegative ||
                redis.probeInterval.isZero ||
                redis.maxInFlightProbes <= 0 ||
                redis.quotaCapacity <= 0 ||
                redis.quotaPeriod.isNegative ||
                redis.quotaPeriod.isZero ||
                redis.bloomExpectedInsertions <= 0 ||
                redis.bloomFalseProbability <= 0.0 ||
                redis.bloomFalseProbability >= 1.0
        if (invalid) fail(StartupFailureCode.INVALID_RANGE)
    }

    private fun validateWorker(worker: VoucherWorkerProperties) {
        val invalid =
            worker.batchSize !in 1..50 ||
                worker.runDeadline.isNegative ||
                worker.runDeadline.isZero ||
                worker.transactionTimeout.isNegative ||
                worker.transactionTimeout.isZero ||
                worker.transactionTimeout > worker.runDeadline ||
                worker.maxAttempts !in 1..20 ||
                worker.instanceId.isBlank() ||
                worker.instanceId.length > 128 ||
                worker.interval.isNegative ||
                worker.interval.isZero ||
                worker.initialDelay.isNegative
        if (invalid) fail(StartupFailureCode.INVALID_RANGE)
    }

    private fun validateSse(sse: VoucherSseProperties) {
        val invalid =
            sse.maxCampaigns !in 1..32 ||
                sse.queueSize !in 1..32 ||
                sse.pollInterval.isNegative ||
                sse.pollInterval.isZero ||
                sse.maxIdleInterval < sse.pollInterval ||
                sse.maxIdleInterval > Duration.ofSeconds(2) ||
                sse.heartbeatInterval.isNegative ||
                sse.heartbeatInterval.isZero ||
                sse.writeTimeout.isNegative ||
                sse.writeTimeout.isZero ||
                sse.maxRows !in 1..200 ||
                sse.maxPayloadBytes !in 1..(256 * 1024)
        if (invalid) fail(StartupFailureCode.INVALID_RANGE)
    }

    private fun validateHttp(http: VoucherHttpProperties) {
        val invalid =
            http.maxHeaderLength !in 8..256 ||
                http.maxScalarBytes !in 32..1024 ||
                http.maxPageSize !in 1..100 ||
                http.maxCursorBytes !in 64..512 ||
                http.operatorSecret.toByteArray(StandardCharsets.UTF_8).size < MINIMUM_KEY_BYTES ||
                http.operatorGuard.length !in 8..64 ||
                http.allowedHosts.isEmpty()
        if (invalid) fail(StartupFailureCode.INVALID_RANGE)
    }

    private fun validateRetention(retention: VoucherRetentionProperties) {
        try {
            VoucherRetentionPolicy(retention)
        } catch (_: IllegalArgumentException) {
            fail(StartupFailureCode.INVALID_RANGE)
        }
    }

    private fun validateReferencedKeys(keys: VoucherKeyProperties) {
        val referenced = referencedKeyVersions.referencedVersions()
        if (!keys.generation.keys.containsAll(referenced.generation) ||
            !keys.verification.keys.containsAll(referenced.verification)
        ) {
            fail(StartupFailureCode.REFERENCED_KEY_MISSING)
        }
    }

    private fun validateBind(runtime: VoucherRuntimeEnvironment) {
        if (runtime.isProduction() && runtime.activeProfiles.any { it in DEMO_PROFILES } && !runtime.isLoopback()) {
            fail(StartupFailureCode.PUBLIC_DEMO_BIND)
        }
    }

    private fun VoucherRuntimeEnvironment.isProduction(): Boolean =
        activeProfiles.any { it == "prod" || it == "production" }

    private fun VoucherRuntimeEnvironment.isLoopback(): Boolean =
        serverAddress == "127.0.0.1" || serverAddress == "::1" || serverAddress == "localhost"

    private fun isKnownTestKey(value: String): Boolean {
        val normalized = value.lowercase()
        return normalized.startsWith("test-") || normalized.startsWith("local-") || "known-test" in normalized
    }

    private fun fail(code: StartupFailureCode): Nothing = throw VoucherStartupException(code)

    companion object {
        private const val HIKARI_MAXIMUM_POOL_SIZE = 16
        private const val BACKGROUND_PERMITS = 4
        private const val MINIMUM_KEY_BYTES = 32
        private val DEMO_PROFILES = setOf("demo", "test", "local")
    }
}

/** Java 25 executor, Exposed transaction boundary, bounded JDBC lane을 제공합니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherProperties::class)
internal class VoucherConfiguration {
    init {
        // Spring Framework 7은 기본적으로 body-stream flush를 비활성화합니다. SSE는 각 event가 network에 도달해야 합니다.
        SpringProperties.setProperty(SPRING_HTTP_RESPONSE_FLUSH_ENABLED, "true")
    }

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean
    fun voucherExposedDatabaseRegistration(
        @Qualifier("springTransactionManager")
        springTransactionManager: PlatformTransactionManager,
    ): VoucherExposedDatabaseRegistration =
        VoucherExposedDatabaseRegistration(
            checkNotNull(
                TransactionTemplate(springTransactionManager).execute {
                    TransactionManager.current().db
                },
            ),
        )

    @Bean(destroyMethod = "")
    fun voucherExecutor(): ExecutorService =
        VirtualThreads.executorService().also {
            log.info { "voucher_executor_created virtualThreads=true" }
        }

    @Bean
    fun voucherExecutorShutdown(executor: ExecutorService): VoucherExecutorShutdown =
        VoucherExecutorShutdown(executor)

    @Bean
    fun databasePermitGate(
        properties: VoucherProperties,
        metrics: VoucherMetrics,
    ): DatabasePermitGate =
        DatabasePermitGate(
            foregroundPermits = properties.db.foregroundPermits,
            workerPermits = 1,
            sseMaintenancePermits = properties.db.backgroundPermits - 1,
            acquireTimeout = properties.db.permitTimeout,
            metrics = metrics,
        )

    @Bean
    fun voucherJdbcExecutor(
        gate: DatabasePermitGate,
        @Qualifier("springTransactionManager")
        transactionManager: PlatformTransactionManager,
        properties: VoucherProperties,
    ): VoucherJdbcExecutor = VoucherJdbcExecutor(gate, transactionManager, properties.db.lockTimeout)

    @Bean
    fun voucherCodeService(properties: VoucherProperties): VoucherCodeService =
        VoucherCodeService(
            VoucherCodeKeyRing(
                currentGenerationVersion = properties.keys.currentVersion,
                currentVerificationVersion = properties.keys.currentVersion,
                generationKeys = properties.keys.generation.mapValues { it.value.toByteArray(StandardCharsets.UTF_8) },
                verificationKeys = properties.keys.verification.mapValues { it.value.toByteArray(StandardCharsets.UTF_8) },
            ),
        )

    @Bean
    fun voucherMigrationRunner(
        dataSource: DataSource,
        properties: VoucherProperties,
    ): VoucherMigrationRunner =
        VoucherMigrationRunner(
            dataSource = dataSource,
            migration = VoucherMigration("001", ClassPathResource("db/migration/V001__voucher_campaign.sql")),
            advisoryLockKey = VOUCHER_MIGRATION_LOCK_KEY,
            lockTimeout = properties.db.lockTimeout,
        )

    @Bean
    fun voucherSchemaMigration(runner: VoucherMigrationRunner): SmartInitializingSingleton =
        SmartInitializingSingleton {
            val result = runner.migrate()
            log.info { "voucher_schema_migration_completed result=$result" }
        }

    @Bean
    fun referencedKeyVersionSource(dataSource: DataSource): ReferencedKeyVersionSource =
        PostgresReferencedKeyVersionSource(dataSource)

    @Bean
    fun voucherStartupValidation(
        properties: VoucherProperties,
        environment: Environment,
        referencedKeys: ObjectProvider<ReferencedKeyVersionSource>,
    ): SmartInitializingSingleton =
        SmartInitializingSingleton {
            val activeProfiles = environment.activeProfiles.toSet().ifEmpty { environment.defaultProfiles.toSet() }
            val runtime =
                VoucherRuntimeEnvironment(
                    activeProfiles = activeProfiles,
                    serverAddress = environment.getProperty("server.address", "127.0.0.1"),
                    hikariMaximumPoolSize =
                        environment.getProperty(
                            "spring.datasource.hikari.maximum-pool-size",
                            Int::class.java,
                            16,
                        ),
                )
            val validator = VoucherStartupValidator(referencedKeys.getIfAvailable() ?: ReferencedKeyVersionSource.NONE)
            try {
                validator.validate(properties, runtime)
            } catch (failure: RuntimeException) {
                log.warn { "voucher_startup_validation_failed code=${sanitizedStartupCode(failure)}" }
                throw failure
            }
            log.info { "voucher_startup_validation_passed profiles=${activeProfiles.sorted()}" }
        }

    companion object : KLogging() {
        private const val SPRING_HTTP_RESPONSE_FLUSH_ENABLED = "spring.http.response.flush.enabled"
        private const val VOUCHER_MIGRATION_LOCK_KEY = 534001L
    }
}

/** Spring context가 닫힐 때 Exposed process-wide database registration을 제거합니다. */
internal class VoucherExposedDatabaseRegistration(
    private val database: Database,
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    init {
        TransactionManager.defaultDatabase = database
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            TransactionManager.closeAndUnregister(database)
        }
    }
}

/** application-owned virtual-thread executor에 bounded shutdown을 적용합니다. */
internal class VoucherExecutorShutdown(
    private val executor: ExecutorService,
    private val timeout: Duration = Duration.ofSeconds(10),
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn { "voucher_executor_forced_shutdown timeoutMillis=${timeout.toMillis()}" }
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        log.info { "voucher_executor_shutdown" }
    }

    companion object : KLogging()
}
