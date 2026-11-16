package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigest
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventKey
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEventType
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceCommand
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceTables
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldServiceCommandServiceTest {

    private companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

    private val repository = FieldServiceRepository()
    private lateinit var service: FieldServiceCommandService

    @BeforeAll
    fun connect() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username.requireNotNull("username"),
            password = postgres.password.requireNotNull("password")
        )
        service = FieldServiceCommandService(repository)
    }

    @BeforeEach
    fun schema() {
        transaction {
            SchemaUtils.drop(*FieldServiceTables.all.reversedArray())
            SchemaUtils.create(*FieldServiceTables.all)
        }
    }

    @Test
    fun `duplicate command is a no-op and changed digest is event key conflict`() {
        val command = command(EventDigest("a".repeat(64)))

        service.accept(command) shouldBeEqualTo CommandResult.APPLIED
        service.accept(command) shouldBeEqualTo CommandResult.DUPLICATE

        service.accept(command.copy(digest = EventDigest("b".repeat(64)))) shouldBeEqualTo CommandResult.EVENT_KEY_REUSED

        transaction {
            repository.countEvents()
        } shouldBeEqualTo 1L
    }

    private fun command(digest: EventDigest) =
        FieldServiceCommand(
            aggregateType = "visit",
            aggregateId = AggregateId("visit-1"),
            eventKey = EventKey("urgent-1"),
            eventType = FieldServiceEventType.VISIT_URGENT,
            digest = digest,
            payloadSummary = "visit-1",
        )
}
