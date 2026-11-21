@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.InboxStatus
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditTable
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherInboxAppliedEvent
import io.bluetape4k.workshop.commerce.voucher.reconciliation.VoucherReconciliationService
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.modulith.events.EventPublication.Status
import org.springframework.modulith.events.core.PublicationTargetIdentifier
import org.springframework.modulith.events.core.TargetEventPublication
import java.io.Serializable
import java.time.Instant
import java.util.*

/**
 * VoucherContextRestartIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherContextRestartIntegrationTest {

    companion object: KLogging()

/**
 * postgres은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val postgres = PostgreSQLServer.Launcher.postgres
/**
 * applicationJdbcUrl은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val applicationJdbcUrl = VoucherTestSchema.create("voucher_restart")

    @Test
/**
 * 이 시나리오는 "pending inbox and Modulith publication repository survive context restart" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `pending inbox and Modulith publication repository survive context restart`() {
        val tenant = Base58.randomString(8)
        val eventId = Uuid.V7.nextId()
        var publicationId: UUID? = null

        startContext().use { first ->
            val publications = first.getBean<ExposedEventPublicationRepository>()
            val publication = TargetEventPublication.of(
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
            val publications = restarted.getBean<ExposedEventPublicationRepository>()
            publications.findByStatus(Status.FAILED).single { it.identifier == publicationId }
            publications.markCompleted(checkNotNull(publicationId), Instant.now())
            publications.findByStatus(Status.COMPLETED).single { it.identifier == publicationId }
            val result = restarted.getBean<VoucherReconciliationService>()
                .runBatch(50, 10.seconds())

            check(result.processed >= 1)
            readInbox(restarted, tenant, eventId).status shouldBeEqualTo InboxStatus.APPLIED
            queryAuditCount(restarted, tenant) shouldBeEqualTo 1L

            await atMost 5.seconds() untilAsserted {
                publications.findCompletedPublications().any { publication ->
                    val event = publication.event as? VoucherInboxAppliedEvent
                    event?.eventId == eventId
                }.shouldBeTrue()
            }
        }
    }

/**
 * seedPending은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun seedPending(
        context: ConfigurableApplicationContext,
        tenant: String,
        eventId: UUID,
    ) {
/**
 * jdbc은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val jdbc = context.getBean<VoucherJdbcExecutor>()
/**
 * inbox은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val inbox = context.getBean<EventInboxRepository>()

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

/**
 * readInbox은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun readInbox(
        context: ConfigurableApplicationContext,
        tenant: String,
        eventId: UUID,
    ): EventInboxRecord {
/**
 * jdbc은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val jdbc = context.getBean<VoucherJdbcExecutor>()
/**
 * inbox은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val inbox = context.getBean<EventInboxRepository>()

        return jdbc.foregroundTransaction {
            inbox.findEvent(tenant, eventId).shouldNotBeNull()
        }
    }

/**
 * startContext은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * queryAuditCount은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun queryAuditCount(context: ConfigurableApplicationContext, tenant: String): Long {
        val jdbc = context.getBean<VoucherJdbcExecutor>()
        return jdbc.foregroundTransaction {
            AuditTable.selectAll().where { AuditTable.tenantId eq tenant }.count()
        }
    }

/**
 * RestartPublication 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class RestartPublication(val eventId: String): Serializable
}
