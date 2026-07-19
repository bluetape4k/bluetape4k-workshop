package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.InboxStatus
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherInboxAppliedEvent
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationService
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.modulith.events.EventPublication.Status
import org.springframework.modulith.events.core.PublicationTargetIdentifier
import org.springframework.modulith.events.core.TargetEventPublication
import java.io.Serializable
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class VoucherContextRestartIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres

    @Test
    fun `pending inbox and Modulith publication repository survive context restart`() {
        val schema = "voucher_restart_${Base58.randomString(8).lowercase()}"
        val eventId = Uuid.V7.nextId()
        var publicationId: UUID? = null
        createSchema(schema)
        try {
            startContext(schema).use { first ->
                val publications = first.getBean(ExposedEventPublicationRepository::class.java)
                val publication =
                    TargetEventPublication.of(
                        RestartPublication(eventId.toString()),
                        PublicationTargetIdentifier.of("voucher.restart.listener"),
                        Instant.now(),
                    )
                publications.create(publication)
                publications.markFailed(publication.identifier)
                publicationId = publication.identifier
                seedPending(first, eventId)
            }

            startContext(schema).use { restarted ->
                val publications = restarted.getBean(ExposedEventPublicationRepository::class.java)
                publications.findByStatus(Status.FAILED).single().identifier shouldBeEqualTo publicationId
                publications.markCompleted(checkNotNull(publicationId), Instant.now())
                publications.findByStatus(Status.COMPLETED).single().identifier shouldBeEqualTo publicationId
                val result = restarted.getBean(VoucherReconciliationService::class.java)
                    .runBatch(50, Duration.ofSeconds(10))
                result.processed shouldBeEqualTo 1
                readInbox(restarted, eventId).status shouldBeEqualTo InboxStatus.APPLIED
                queryLong(schema, "SELECT count(*) FROM voucher_audits") shouldBeEqualTo 1L
                await atMost Duration.ofSeconds(5) untilAsserted {
                    publications.findCompletedPublications().any { publication ->
                        val event = publication.event as? VoucherInboxAppliedEvent
                        event?.eventId == eventId
                    } shouldBeEqualTo true
                }
            }
        } finally {
            dropSchema(schema)
        }
    }

    private fun seedPending(
        context: ConfigurableApplicationContext,
        eventId: UUID,
    ) {
        val jdbc = context.getBean(VoucherJdbcExecutor::class.java)
        val inbox = context.getBean(EventInboxRepository::class.java)
        jdbc.foregroundTransaction {
            inbox.insert(
                EventInboxRecord(
                    id = 0,
                    tenantId = "tenant-restart",
                    eventId = eventId,
                    aggregateType = "CAMPAIGN",
                    aggregateId = Uuid.V7.nextId(),
                    payloadDigest = eventId.toString().replace("-", "").repeat(2),
                    observedSequence = 1,
                    status = InboxStatus.PENDING,
                    attempt = 0,
                    nextAttemptAt = Instant.now(),
                    claimOwner = null,
                    claimUntil = null,
                ),
            )
        }
    }

    private fun readInbox(
        context: ConfigurableApplicationContext,
        eventId: UUID,
    ): EventInboxRecord {
        val jdbc = context.getBean(VoucherJdbcExecutor::class.java)
        val inbox = context.getBean(EventInboxRepository::class.java)
        return jdbc.foregroundTransaction { checkNotNull(inbox.findEvent("tenant-restart", eventId)) }
    }

    private fun startContext(schema: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(VoucherCampaignApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=${schemaJdbcUrl(schema)}",
                "--spring.datasource.username=${requireNotNull(postgres.username)}",
                "--spring.datasource.password=${requireNotNull(postgres.password)}",
                "--spring.datasource.hikari.minimum-idle=0",
                "--spring.main.banner-mode=off",
                "--workshop.voucher.redis.enabled=false",
            )

    private fun queryLong(
        schema: String,
        sql: String,
    ): Long =
        DriverManager.getConnection(
            schemaJdbcUrl(schema),
            requireNotNull(postgres.username),
            requireNotNull(postgres.password),
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }

    private fun createSchema(schema: String) =
        postgresConnection().use { connection -> connection.createStatement().use { it.execute("CREATE SCHEMA $schema") } }

    private fun dropSchema(schema: String) =
        postgresConnection().use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }

    private fun postgresConnection() =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            requireNotNull(postgres.username),
            requireNotNull(postgres.password),
        )

    private fun schemaJdbcUrl(schema: String): String {
        val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
        return "${postgres.jdbcUrl}${separator}currentSchema=$schema"
    }

    private data class RestartPublication(val eventId: String) : Serializable
}
