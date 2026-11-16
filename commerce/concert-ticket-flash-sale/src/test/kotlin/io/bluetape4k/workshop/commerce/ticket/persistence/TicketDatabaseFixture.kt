package io.bluetape4k.workshop.commerce.ticket.persistence

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getDataSource
import io.bluetape4k.workshop.commerce.ticket.config.TicketMigration
import io.bluetape4k.workshop.commerce.ticket.config.TicketMigrationRunner
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import org.springframework.core.io.ClassPathResource
import java.sql.DriverManager
import java.time.Instant
import java.util.*

internal class TicketDatabaseFixture: AutoCloseable {

    companion object: KLogging() {
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        const val MAXIMUM_POOL_SIZE = 8
    }

    private val schema = "ticket_repository_${Base58.randomString(8).lowercase()}"
    val dataSource: HikariDataSource
    val executor: TicketJdbcExecutor

    init {
        adminConnection().use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        dataSource = postgres.getDataSource {
            schema = this@TicketDatabaseFixture.schema
            maximumPoolSize = MAXIMUM_POOL_SIZE
            poolName = "ticket-test-$schema"
        }
        TicketMigrationRunner(
            dataSource = dataSource,
            migration = TicketMigration("001", ClassPathResource("db/migration/V001__concert_ticket_flash_sale.sql")),
            advisoryLockKey = 521_101L,
        ).migrate()
        executor = TicketJdbcExecutor(dataSource, foregroundPermits = 2)
    }

    fun seedAuthority(
        saleId: UUID,
        userSubjectId: UUID,
        ipSubjectId: UUID,
        firstAttemptId: UUID,
        secondAttemptId: UUID,
    ) {
        val now = Instant.now()

        executor.transaction {
            TicketSaleEntity.new(saleId) {
                state = SaleState.OPEN.code
                currentPolicyVersion = 1L
                opensAt = now.minusSeconds(60)
                closesAt = now.plusSeconds(3_600)
                revision = 0L
                createdAt = now
                updatedAt = now
            }
            TicketSalePolicyEntity.new {
                this.saleId = saleId
                policyVersion = 1L
                perUserLimit = 1
                maxQuantity = 4
                holdSeconds = 30L
                createdAt = now
            }
            TicketInventoryEntity.new {
                this.saleId = saleId
                grade = "GENERAL"
                totalQuantity = 10
                heldQuantity = 0
                soldQuantity = 0
                revision = 0L
            }
            TicketIdentitySubjectEntity.new(userSubjectId) {
                identityKind = IdentityKind.USER.name
                createdAt = now
                anonymizedAt = null
            }
            TicketIdentitySubjectEntity.new(ipSubjectId) {
                identityKind = IdentityKind.IP.name
                createdAt = now
                anonymizedAt = null
            }
            listOf(firstAttemptId, secondAttemptId).forEach { attemptId ->
                TicketPurchaseAttemptEntity.new(attemptId) {
                    this.saleId = saleId
                    this.userSubjectId = userSubjectId
                    this.ipSubjectId = ipSubjectId
                    grade = "GENERAL"
                    quantity = 1
                    policyVersion = 1L
                    state = PurchaseState.INVENTORY_HELD.code
                    holdDeadline = now.plusSeconds(30)
                    authorizationOperationId = Uuid.V7.nextId()
                    revision = 0L
                    createdAt = now
                    updatedAt = now
                }
            }
        }
    }

    fun seedSale(
        saleId: UUID,
        totalQuantity: Int? = null,
    ) {
        val now = Instant.now()

        executor.transaction {
            TicketSaleEntity.new(saleId) {
                state = SaleState.OPEN.code
                currentPolicyVersion = 1L
                opensAt = now
                closesAt = now.plusSeconds(3_600)
                revision = 0L
                createdAt = now
                updatedAt = now
            }
            totalQuantity?.let { quantity ->
                TicketInventoryEntity.new {
                    this.saleId = saleId
                    this.grade = "GENERAL"
                    this.totalQuantity = quantity
                    this.heldQuantity = 0
                    this.soldQuantity = 0
                    this.revision = 0L
                }
            }
        }
    }

    fun seedIdentitySubject(
        subjectId: UUID,
        identityKind: IdentityKind = IdentityKind.USER,
    ) {
        val now = Instant.now()
        executor.transaction {
            TicketIdentitySubjectEntity.new(subjectId) {
                this.identityKind = identityKind.name
                this.createdAt = now
                this.anonymizedAt = null
            }
        }
    }

    fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(sql.requireNotBlank("sql"))
            }
        }
    }

    override fun close() {
        try {
            executor.close()
            dataSource.close()
        } finally {
            adminConnection().use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
        }
    }

    private fun adminConnection() =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )
}
