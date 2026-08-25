package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.shiftcoverage.application.IdempotencyClaimKind
import io.bluetape4k.workshop.optimization.shiftcoverage.application.IdempotencyNamespace
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.IdempotencyKey
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftCoveragePostgresIdempotencyRepositoryTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val repository = ShiftCoveragePostgresIdempotencyRepository()
    private val namespace = IdempotencyNamespace("POST", "/replans", "site-demo", "manager-demo", IdempotencyKey("pg-idempotency"))
    private val fingerprint = "a".repeat(64)

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = requireNotNull(postgres.username),
            password = requireNotNull(postgres.password),
        )
    }

    @BeforeEach
    fun createSchema() {
        transaction {
            SchemaUtils.drop(ShiftCoverageIdempotencyTable)
            SchemaUtils.create(ShiftCoverageIdempotencyTable)
        }
    }

    @AfterAll
    fun dropSchema() {
        transaction { SchemaUtils.drop(ShiftCoverageIdempotencyTable) }
    }

    @Test
    fun `postgres claim survives repository instance boundary and mismatched fingerprint is no write`() {
        repository.begin(namespace, fingerprint).kind shouldBeEqualTo IdempotencyClaimKind.NEW
        repository.complete(namespace, "accepted|request-1")

        val replay = ShiftCoveragePostgresIdempotencyRepository().begin(namespace, fingerprint)
        replay.kind shouldBeEqualTo IdempotencyClaimKind.REPLAY
        replay.response shouldBeEqualTo "accepted|request-1"

        ShiftCoveragePostgresIdempotencyRepository().begin(namespace, "b".repeat(64)).kind shouldBeEqualTo IdempotencyClaimKind.REUSED
    }
}
