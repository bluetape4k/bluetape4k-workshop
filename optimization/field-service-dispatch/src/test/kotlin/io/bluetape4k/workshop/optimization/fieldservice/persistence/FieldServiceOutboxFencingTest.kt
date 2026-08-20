package io.bluetape4k.workshop.optimization.fieldservice.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class FieldServiceOutboxFencingTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private val clock = MutableTestClock(Instant.parse("2026-08-20T00:00:00Z"))
    private val repository = FieldServiceRepository(clock)

    @BeforeAll
    fun connect() {
        Database.connect(postgres.jdbcUrl, "org.postgresql.Driver", requireNotNull(postgres.username), requireNotNull(postgres.password))
    }

    @BeforeEach
    fun schema() {
        transaction {
            SchemaUtils.drop(*FieldServiceTables.all.reversedArray())
            SchemaUtils.create(*FieldServiceTables.all)
        }
    }

    @Test
    fun `expired lease is fenced and poison item reaches dead letter`() {
        val first = transaction {
            repository.enqueueOutbox(OutboxRecord(payload = "lease", nextAttemptAt = clock.instant()))
            repository.claimOutbox(owner = "worker-1").single()
        }
        clock.advanceSeconds(31)
        val second = transaction { repository.claimOutbox(owner = "worker-2").single() }
        transaction {
            repository.completeOutbox(first.id, "worker-1", first.leaseToken.orEmpty()).shouldBeFalse()
            repository.renewOutbox(first.id, "worker-1", first.leaseToken.orEmpty()).shouldBeFalse()
            repository.completeOutbox(second.id, "worker-2", second.leaseToken.orEmpty()).shouldBeTrue()
        }

        val poison = transaction {
            repository.enqueueOutbox(OutboxRecord(payload = "poison", nextAttemptAt = clock.instant()))
        }
        repeat(5) { attempt ->
            val claimed = transaction { repository.claimOutbox(owner = "poison-worker").single { it.id == poison.id } }
            transaction {
                repository.retryOutbox(claimed.id, "poison-worker", claimed.leaseToken.orEmpty(), "synthetic failure")
                    .shouldBeTrue()
            }
            clock.advanceSeconds((2L shl (attempt + 1)).coerceAtMost(60L) + 1L)
        }
        transaction { repository.claimOutbox(owner = "poison-worker").size shouldBeEqualTo 0 }
    }

    private class MutableTestClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advanceSeconds(seconds: Long) { current = current.plusSeconds(seconds) }
    }
}
