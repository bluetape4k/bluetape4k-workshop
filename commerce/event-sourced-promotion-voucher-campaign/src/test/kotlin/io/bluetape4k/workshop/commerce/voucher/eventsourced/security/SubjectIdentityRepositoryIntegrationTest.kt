package io.bluetape4k.workshop.commerce.voucher.eventsourced.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SubjectIdentityRepositoryIntegrationTest {

    companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }

        private val keyRing = EventSourcedHmacKeyRing(
            active = EventSourcedHmacKey(2, "active-key-material-with-at-least-32-bytes".toUtf8Bytes()),
            retired = listOf(
                EventSourcedHmacKey(1, "retired-key-material-with-at-least-32-bytes".toUtf8Bytes()),
            ),
        )
    }

    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database
    private lateinit var repository: SubjectIdentityRepository

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-subject-identity")
        database = postgresDatabase.database
        repository = SubjectIdentityRepository(keyRing)
    }

    @AfterAll
    fun closeDataSource() {
        postgresDatabase.close()
    }

    @BeforeEach
    fun createSchema() {
        transaction(database) {
            SchemaUtils.drop(SubjectIdentityMappings)
            SchemaUtils.create(SubjectIdentityMappings)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction(database) { SchemaUtils.drop(SubjectIdentityMappings) }
    }

    @Test
    fun `erasure removes reverse lookup and registering the same identity creates a new surrogate`() {
        val tenantId = TenantId("tenant-a")
        val first = transaction(database) {
            repository.resolve(tenantId, "campaign-principal", "user-42")
        }
        log.debug { "first: $first" }

        transaction(database) {
            repository.findBySurrogate(tenantId, first.surrogate) shouldBeEqualTo first
        }

        val logger = LoggerFactory.getLogger(SubjectIdentityRepository::class.java) as Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.INFO
        logger.addAppender(appender)

        try {
            transaction(database) {
                repository.erase(tenantId, "campaign-principal", "user-42")
            } shouldBeEqualTo 1
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
        appender.list.joinToString("\n", transform = ILoggingEvent::getFormattedMessage) shouldNotContain "user-42"

        transaction(database) {
            repository.findBySurrogate(tenantId, first.surrogate)
        }.shouldBeNull()

        val second = transaction(database) {
            repository.resolve(tenantId, "campaign-principal", "user-42")
        }

        log.debug { "second: $second" }
        second.surrogate shouldNotBeEqualTo first.surrogate
        second.identityDigest shouldBeEqualTo first.identityDigest
        second.hmacKeyVersion shouldBeEqualTo 2
    }

    @Test
    fun `retired key mapping remains resolvable without creating a duplicate surrogate`() {
        val tenantId = TenantId("tenant-a")
        val retiredDigest = keyRing.digestWithVersion(
            keyVersion = 1,
            purpose = HmacPurpose.SUBJECT_IDENTITY,
            tenantId = tenantId,
            domain = "campaign-principal",
            value = "user-42",
        )
        val existing = transaction(database) {
            repository.insert(retiredDigest, tenantId)
        }
        log.debug { "existing: $existing" }

        val resolved = transaction(database) {
            repository.resolve(tenantId, "campaign-principal", "user-42")
        }

        log.debug { "resolved: $resolved" }
        resolved shouldBeEqualTo existing

        transaction(database) {
            repository.countFor(tenantId, "campaign-principal", "user-42")
        } shouldBeEqualTo 1L
    }
}
