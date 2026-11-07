package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftCoveragePostgresLockTimeoutTest {

    companion object: KLogging() {
        private val postgres = PostgreSQLServer.Launcher.postgres
    }

    private val repository = ShiftCoverageAggregateRepository()

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username.shouldNotBeNull(),
            password = postgres.password.shouldNotBeNull(),
        )
    }

    @BeforeEach
    fun createSchema() {
        transaction {
            SchemaUtils.drop(*ShiftCoverageTables.all.reversedArray())
            SchemaUtils.create(*ShiftCoverageTables.all)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction {
            SchemaUtils.drop(*ShiftCoverageTables.all.reversedArray())
        }
    }

    @Test
    fun `uuid aggregate and revision lookup are authoritative in postgres`() {
        val record = ShiftCoverageAggregateRecord(
            siteId = "site-a",
            planId = "plan-a",
            revision = 7L,
            snapshotDigest = "a".repeat(64),
            payload = "fixture",
        )

        transaction {
            val saved = repository.save(record)

            log.debug { "saved=$saved" }
            saved.id shouldBeEqualTo record.id

            repository.findByPlanRevision("site-a", "plan-a", 7L)
                ?.payload shouldBeEqualTo "fixture"
        }
    }

    @Test
    fun `mutation transaction applies postgres local timeout contract`() {
        val settings = ShiftCoverageTransactionSupport.inMutation {
            val transaction = TransactionManager.current()
            val lockTimeout = transaction.exec("SHOW lock_timeout") { result ->
                check(result.next())
                result.getString(1)
            }
            val statementTimeout = transaction.exec("SHOW statement_timeout") { result ->
                check(result.next())
                result.getString(1)
            }
            lockTimeout to statementTimeout
        }

        log.debug { "settings=$settings" }
        settings shouldBeEqualTo ("2s" to "5s")
    }
}
