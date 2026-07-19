package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.transaction.PlatformTransactionManager
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

/** Applies fail-closed production checks without exposing secret values in the exception. */
internal class VoucherStartupValidator(
    private val referencedKeyVersions: ReferencedKeyVersionSource = ReferencedKeyVersionSource.NONE,
) {
    fun validate(
        properties: VoucherProperties,
        runtime: VoucherRuntimeEnvironment,
    ) {
        validateDatabase(properties.db, runtime.hikariMaximumPoolSize)
        validateRedis(properties.redis)
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

/** Provides the Java 25 executor, Exposed transaction boundary, and bounded JDBC lanes. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoucherProperties::class)
internal class VoucherConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean(destroyMethod = "")
    fun voucherExecutor(): ExecutorService =
        VirtualThreads.executorService().also {
            log.info { "voucher_executor_created virtualThreads=true" }
        }

    @Bean
    fun voucherExecutorShutdown(executor: ExecutorService): VoucherExecutorShutdown =
        VoucherExecutorShutdown(executor)

    @Bean
    fun databasePermitGate(properties: VoucherProperties): DatabasePermitGate =
        DatabasePermitGate(
            foregroundPermits = properties.db.foregroundPermits,
            workerPermits = 1,
            sseMaintenancePermits = properties.db.backgroundPermits - 1,
            acquireTimeout = properties.db.permitTimeout,
        )

    @Bean
    fun voucherJdbcExecutor(
        gate: DatabasePermitGate,
        transactionManager: PlatformTransactionManager,
        properties: VoucherProperties,
    ): VoucherJdbcExecutor = VoucherJdbcExecutor(gate, transactionManager, properties.db.lockTimeout)

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
        private const val VOUCHER_MIGRATION_LOCK_KEY = 534001L
    }
}

/** Gives the application-owned virtual-thread executor a bounded shutdown. */
internal class VoucherExecutorShutdown(
    private val executor: ExecutorService,
    private val timeout: Duration = Duration.ofSeconds(10),
) : AutoCloseable {
    override fun close() {
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
