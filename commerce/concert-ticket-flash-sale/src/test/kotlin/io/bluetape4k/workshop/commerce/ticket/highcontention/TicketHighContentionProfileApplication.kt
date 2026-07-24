package io.bluetape4k.workshop.commerce.ticket.highcontention

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.codec.Base58
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.ticket.admission.internal.AdmissionService
import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.config.TicketMigration
import io.bluetape4k.workshop.commerce.ticket.config.TicketMigrationRunner
import io.bluetape4k.workshop.commerce.ticket.config.TicketProperties
import io.bluetape4k.workshop.commerce.ticket.config.TicketPublicationConfiguration
import io.bluetape4k.workshop.commerce.ticket.config.TicketRedisConfiguration
import io.bluetape4k.workshop.commerce.ticket.config.TicketRedisResources
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyScope
import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.purchase.api.AuthorizationRequested
import io.bluetape4k.workshop.commerce.ticket.purchase.api.StartPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseEventPublisher
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseService
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePolicySnapshot
import io.bluetape4k.workshop.commerce.ticket.salecontrol.internal.SaleService
import io.bluetape4k.workshop.commerce.ticket.redis.ForegroundLeaseGate
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.FakeTicketProvider
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.TicketEffectWorker
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

internal class TicketHighContentionProfileApplication private constructor(
    private var applicationContext: ConfigurableApplicationContext,
    private val schema: String,
    private val redisUri: String,
    private val databasePermitTimeout: Duration,
    private val events: MutableList<AuthorizationRequested>,
) : AutoCloseable {
    val dataSource: HikariDataSource
        get() = applicationContext.getBean(DataSource::class.java) as HikariDataSource
    val jdbc: TicketJdbcExecutor
        get() = applicationContext.getBean(TicketJdbcExecutor::class.java)
    val purchases: PurchaseService
        get() = applicationContext.getBean(PurchaseService::class.java)
    val paymentWorker: PaymentWorker
        get() = applicationContext.getBean(PaymentWorker::class.java)
    val paymentProvider: FakePaymentProvider
        get() = applicationContext.getBean(FakePaymentProvider::class.java)
    val ticketWorker: TicketEffectWorker
        get() = applicationContext.getBean(TicketEffectWorker::class.java)
    val ticketProvider: FakeTicketProvider
        get() = applicationContext.getBean(FakeTicketProvider::class.java)
    val redisResources: TicketRedisResources
        get() = applicationContext.getBean(TicketRedisResources::class.java)
    val foregroundLeaseGate: ForegroundLeaseGate
        get() = applicationContext.getBean(ForegroundLeaseGate::class.java)

    fun capturedEvents(): List<AuthorizationRequested> = events.toList()

    fun createSale(
        namespace: String,
        inventory: Int,
    ): TicketProfileSale {
        namespace.requireNotBlank("namespace")
        require(inventory > 0) { "inventory must be positive" }
        val saleId = stableUuid("$namespace:sale")
        val sharedIp = stableUuid("$namespace:shared-ip")
        val now = Instant.now()
        execute(
            """
            INSERT INTO ticket_sales(sale_id, state, current_policy_version, opens_at, closes_at)
            VALUES ('$saleId', 'open', 1, '${now.minusSeconds(60)}', '${now.plusSeconds(3600)}');
            INSERT INTO ticket_sale_policy_versions(sale_id, policy_version, per_user_limit, max_quantity, hold_seconds)
            VALUES ('$saleId', 1, 4, 4, 30);
            INSERT INTO ticket_inventory(sale_id, grade, total_quantity) VALUES ('$saleId', 'GENERAL', $inventory);
            INSERT INTO ticket_identity_subjects(subject_id, identity_kind) VALUES ('$sharedIp', 'IP');
            """.trimIndent(),
        )
        return TicketProfileSale(namespace, saleId, sharedIp, now)
    }

    fun command(
        sale: TicketProfileSale,
        identityOrdinal: Int,
        attemptOrdinal: Int = 0,
        shareIp: Boolean = false,
    ): StartPurchase {
        require(identityOrdinal >= 0) { "identityOrdinal must be non-negative" }
        require(attemptOrdinal >= 0) { "attemptOrdinal must be non-negative" }
        val buyer = stableUuid("${sale.namespace}:buyer:$identityOrdinal")
        val ip = if (shareIp) sale.sharedIp else stableUuid("${sale.namespace}:ip:$identityOrdinal")
        val attemptId = stableUuid("${sale.namespace}:attempt:$identityOrdinal:$attemptOrdinal")
        val grantNonce = stableUuid("${sale.namespace}:grant:$identityOrdinal:$attemptOrdinal")
        execute(
            """
            INSERT INTO ticket_identity_subjects(subject_id, identity_kind)
            VALUES ('$buyer', 'USER') ON CONFLICT DO NOTHING;
            INSERT INTO ticket_identity_subjects(subject_id, identity_kind)
            VALUES ('$ip', 'IP') ON CONFLICT DO NOTHING;
            INSERT INTO ticket_admission_grants(sale_id, grant_nonce, buyer_subject_id, policy_version, expires_at)
            VALUES ('${sale.saleId}', '$grantNonce', '$buyer', 1, '${sale.now.plusSeconds(300)}');
            """.trimIndent(),
        )
        val owner = HttpIdempotencyRepository(jdbc).acquire(
            IdempotencyScope(
                principalSubjectId = buyer,
                httpMethod = "POST",
                canonicalRoute = "/api/v1/sales/{saleId}/purchase-attempts",
                resourceId = sale.saleId.toString(),
                operation = "purchase",
                keyDigest = IdempotencyFingerprint.key(
                    ByteArray(32) { 0x52 },
                    "${sale.namespace}:key:$identityOrdinal:$attemptOrdinal",
                ),
            ),
            IdempotencyFingerprint.request("POST", "/purchase", "{\"grade\":\"GENERAL\",\"quantity\":1}"),
            sale.now,
        ) as IdempotencyDecision.Owner
        return StartPurchase(
            attemptId = attemptId,
            authorizationOperationId = stableUuid("${sale.namespace}:payment:$identityOrdinal:$attemptOrdinal"),
            idempotencyOwnerId = owner.id,
            buyerSubjectId = buyer,
            ipSubjectId = ip,
            grade = "GENERAL",
            quantity = 1,
            grant = ConsumeGrant(sale.saleId, grantNonce, buyer, 1, attemptId),
            policy = SalePolicySnapshot(
                sale.saleId,
                SaleState.OPEN,
                1,
                sale.now.minusSeconds(60),
                sale.now.plusSeconds(3600),
            ),
        )
    }

    fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    fun queryLong(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }

    fun queryString(sql: String): String =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next())
                    result.getString(1)
                }
            }
        }

    fun queryUuid(sql: String): UUID =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next())
                    result.getObject(1, UUID::class.java)
                }
            }
        }

    fun restart() {
        applicationContext.close()
        applicationContext = startContext(schema, redisUri, databasePermitTimeout, events)
    }

    override fun close() {
        try {
            applicationContext.close()
        } finally {
            dropSchema(schema)
        }
    }

    internal companion object {
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        fun start(
            redisUri: String,
            databasePermitTimeout: Duration = Duration.ofMillis(250),
        ): TicketHighContentionProfileApplication {
            val validRedisUri = redisUri.requireNotBlank("redisUri")
            val validDatabasePermitTimeout =
                databasePermitTimeout.requireGt(Duration.ZERO, "databasePermitTimeout")
            val schema = "ticket_hc_${Base58.randomString(8).lowercase()}"
            adminConnection().use { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            }
            val events = CopyOnWriteArrayList<AuthorizationRequested>()
            return try {
                TicketHighContentionProfileApplication(
                    applicationContext = startContext(
                        schema,
                        validRedisUri,
                        validDatabasePermitTimeout,
                        events,
                    ),
                    schema = schema,
                    redisUri = validRedisUri,
                    databasePermitTimeout = validDatabasePermitTimeout,
                    events = events,
                )
            } catch (error: Throwable) {
                dropSchema(schema)
                throw error
            }
        }

        private fun startContext(
            schema: String,
            redisUri: String,
            databasePermitTimeout: Duration,
            events: MutableList<AuthorizationRequested>,
        ): ConfigurableApplicationContext =
            SpringApplicationBuilder(ProfileSpringApplication::class.java)
                .web(WebApplicationType.NONE)
                .initializers({ context ->
                    context.beanFactory.registerSingleton(PROFILE_EVENTS_BEAN, events)
                })
                .run(
                    "--spring.datasource.url=${schemaJdbcUrl(schema)}",
                    "--spring.datasource.username=${postgres.username ?: PostgreSQLServer.USERNAME}",
                    "--spring.datasource.password=${postgres.password ?: PostgreSQLServer.PASSWORD}",
                    "--spring.datasource.hikari.maximum-pool-size=20",
                    "--spring.datasource.hikari.schema=$schema",
                    "--spring.main.banner-mode=off",
                    "--workshop.ticket.db.permit-timeout=${databasePermitTimeout.toMillis()}ms",
                    "--workshop.ticket.redis.uri=$redisUri",
                )

        private fun schemaJdbcUrl(schema: String): String =
            postgres.jdbcUrl + if ('?' in postgres.jdbcUrl) "&currentSchema=$schema" else "?currentSchema=$schema"

        private fun dropSchema(schema: String) {
            adminConnection().use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }

        private fun adminConnection() =
            DriverManager.getConnection(
                postgres.jdbcUrl,
                postgres.username ?: PostgreSQLServer.USERNAME,
                postgres.password ?: PostgreSQLServer.PASSWORD,
            )

        private fun stableUuid(value: String): UUID =
            UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8))

        private const val PROFILE_EVENTS_BEAN = "ticketHighContentionCapturedEvents"
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TicketProperties::class)
    @ImportAutoConfiguration(DataSourceAutoConfiguration::class)
    @Import(TicketRedisConfiguration::class, ProfileOverrides::class)
    internal class ProfileSpringApplication

    @TestConfiguration(proxyBeanMethods = false)
    internal class ProfileOverrides {
        private val production = TicketPublicationConfiguration()

        @Bean
        fun ticketHighContentionMigration(dataSource: DataSource): SmartInitializingSingleton =
            SmartInitializingSingleton {
                TicketMigrationRunner(
                    dataSource = dataSource,
                    migration = TicketMigration(
                        "001",
                        ClassPathResource("db/migration/V001__concert_ticket_flash_sale.sql"),
                    ),
                    advisoryLockKey = 521_522L,
                ).migrate()
            }

        @Bean
        fun ticketClock(): Clock = production.ticketClock()

        @Bean
        fun ticketJdbcExecutor(dataSource: DataSource, properties: TicketProperties): TicketJdbcExecutor =
            production.ticketJdbcExecutor(dataSource, properties)

        @Bean
        fun ticketSaleService(): SaleService = production.ticketSaleService()

        @Bean
        fun ticketAdmissionService(jdbc: TicketJdbcExecutor, clock: Clock): AdmissionService =
            production.ticketAdmissionService(jdbc, clock)

        @Bean
        @Primary
        fun highContentionPurchaseEventPublisher(
            ticketHighContentionCapturedEvents: MutableList<AuthorizationRequested>,
        ): PurchaseEventPublisher =
            PurchaseEventPublisher { event ->
                ticketHighContentionCapturedEvents += event
            }

        @Bean
        fun purchaseService(
            jdbc: TicketJdbcExecutor,
            sale: SaleService,
            admission: AdmissionService,
            clock: Clock,
            events: PurchaseEventPublisher,
        ): PurchaseService =
            production.purchaseService(jdbc, sale, admission, clock, events)

        @Bean
        fun paymentProvider(): FakePaymentProvider = production.paymentProvider()

        @Bean
        fun paymentWorker(
            jdbc: TicketJdbcExecutor,
            purchases: PurchaseService,
            provider: FakePaymentProvider,
        ): PaymentWorker =
            production.paymentWorker(jdbc, purchases, provider)

        @Bean
        fun ticketProvider(): FakeTicketProvider = production.ticketProvider()

        @Bean
        fun ticketEffectWorker(
            jdbc: TicketJdbcExecutor,
            purchases: PurchaseService,
            provider: FakeTicketProvider,
        ): TicketEffectWorker =
            production.ticketEffectWorker(jdbc, purchases, provider)
    }
}

internal data class TicketProfileSale(
    val namespace: String,
    val saleId: UUID,
    val sharedIp: UUID,
    val now: Instant,
)
