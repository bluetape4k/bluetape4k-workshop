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
    private val applicationJdbcUrl = VoucherTestSchema.create("voucher_restart")

    @Test
    fun `pending inbox and Modulith publication repository survive context restart`() {
        val tenant = Base58.randomString(8)
        val eventId = Uuid.V7.nextId()
        var publicationId: UUID? = null
        startContext().use { first ->
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
            seedPending(first, tenant, eventId)
        }
        startContext().use { restarted ->
            val publications = restarted.getBean(ExposedEventPublicationRepository::class.java)
            publications.findByStatus(Status.FAILED).single { it.identifier == publicationId }
            publications.markCompleted(checkNotNull(publicationId), Instant.now())
            publications.findByStatus(Status.COMPLETED).single { it.identifier == publicationId }
            val result = restarted.getBean(VoucherReconciliationService::class.java)
                .runBatch(50, Duration.ofSeconds(10))
            check(result.processed >= 1)
            readInbox(restarted, tenant, eventId).status shouldBeEqualTo InboxStatus.APPLIED
            queryAuditCount(tenant) shouldBeEqualTo 1L
            await atMost Duration.ofSeconds(5) untilAsserted {
                publications.findCompletedPublications().any { publication ->
                    val event = publication.event as? VoucherInboxAppliedEvent
                    event?.eventId == eventId
                } shouldBeEqualTo true
            }
        }
    }

    private fun seedPending(
        context: ConfigurableApplicationContext,
        tenant: String,
        eventId: UUID,
    ) {
        val jdbc = context.getBean(VoucherJdbcExecutor::class.java)
        val inbox = context.getBean(EventInboxRepository::class.java)
        jdbc.foregroundTransaction {
            inbox.insert(
                EventInboxRecord(
                    id = 0,
                    tenantId = tenant,
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
        tenant: String,
        eventId: UUID,
    ): EventInboxRecord {
        val jdbc = context.getBean(VoucherJdbcExecutor::class.java)
        val inbox = context.getBean(EventInboxRepository::class.java)
        return jdbc.foregroundTransaction { checkNotNull(inbox.findEvent(tenant, eventId)) }
    }

    private fun startContext(): ConfigurableApplicationContext =
        SpringApplicationBuilder(VoucherCampaignApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=$applicationJdbcUrl",
                "--spring.datasource.username=${requireNotNull(postgres.username)}",
                "--spring.datasource.password=${requireNotNull(postgres.password)}",
                "--spring.datasource.hikari.minimum-idle=0",
                "--spring.main.banner-mode=off",
                "--workshop.voucher.redis.enabled=false",
            )

    private fun queryAuditCount(tenant: String): Long =
        DriverManager.getConnection(
            applicationJdbcUrl,
            requireNotNull(postgres.username),
            requireNotNull(postgres.password),
        ).use { connection ->
            connection.prepareStatement("SELECT count(*) FROM voucher_audits WHERE tenant_id = ?").use { statement ->
                statement.setString(1, tenant)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }

    private data class RestartPublication(val eventId: String) : Serializable
}
