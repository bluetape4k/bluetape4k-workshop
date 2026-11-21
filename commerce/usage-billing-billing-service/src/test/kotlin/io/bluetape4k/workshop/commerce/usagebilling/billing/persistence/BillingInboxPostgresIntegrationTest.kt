package io.bluetape4k.workshop.commerce.usagebilling.billing.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.billing.application.BillingInboxService
import io.bluetape4k.workshop.commerce.usagebilling.billing.application.BillingPricingEvidenceService
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidenceEvent
import io.bluetape4k.workshop.commerce.usagebilling.billing.domain.BillingPriceEvidenceOutcome
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.*

@Tag("integration")
@SpringBootTest
@Suppress("VarCouldBeVal") // Spring injects these mutable lateinit collaborators after construction.
/**
 * BillingInboxPostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class BillingInboxPostgresIntegrationTest {

    private companion object: KLogging() {
/**
 * postgres은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        @JvmStatic
        @DynamicPropertySource
/**
 * postgresProperties은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
            registry.add("management.datadog.metrics.export.enabled") { false }
        }
    }

    @Autowired
/**
 * pricingEvidence은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var pricingEvidence: BillingPricingEvidenceService

    @Autowired
/**
 * inbox은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var inbox: BillingInboxService

    @Autowired
/**
 * charges은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var charges: BillingChargeRepository

    @Autowired
/**
 * outbox은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var outbox: BillingOutboxRepository

    @Autowired
/**
 * priceEvidenceInbox은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var priceEvidenceInbox: BillingPriceEvidenceInboxRepository

    @Autowired
/**
 * outboxJournal은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var outboxJournal: ExposedBillingOutboxJournal

    @Autowired
/**
 * transactionManager은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
/**
 * 이 시나리오는 "accepted usage event creates one append-only charge and one pending outbox event" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `accepted usage event creates one append-only charge and one pending outbox event`() {
        val meterCode = "api_calls_${UUID.randomUUID()}"

        pricingEvidence.record(
            BillingPriceEvidence(
                tenantId = "tenant-a",
                meterCode = meterCode,
                currency = "USD",
                unitPrice = BigDecimal("0.10"),
                effectiveAt = Instant.EPOCH
            ),
        )
        val chargeCount = transaction { charges.findAll().count() }
        val outboxCount = transaction { outbox.findAll().count() }

        val event = BillingInboxEvent(
            eventId = UUID.randomUUID(),
            tenantId = "tenant-a",
            aggregateType = "Usage",
            aggregateId = "usage-1",
            aggregateVersion = 1,
            payloadDigest = "digest-a",
            meterCode = meterCode,
            quantity = BigDecimal.TWO,
        )

        inbox.handle(event) shouldBeEqualTo BillingInboxOutcome.APPLIED
        inbox.handle(event) shouldBeEqualTo BillingInboxOutcome.DUPLICATE

        transaction { charges.findAll().count() } shouldBeEqualTo chargeCount + 1
        transaction { charges.findAll().last().amount } shouldBeEqualTo BigDecimal("0.20")
        transaction { outbox.findAll().count() } shouldBeEqualTo outboxCount + 1
        transaction { outbox.findAll().last().status } shouldBeEqualTo "PENDING"

        val payload = transaction { outbox.findAll().last().payload }

        log.debug { "payload=$payload" }
        Jackson.defaultJsonMapper.readTree(payload)
            .get("eventType")!!.asString() shouldBeEqualTo "ChargeRated"
    }

    @Test
/**
 * 이 시나리오는 "price evidence inbox absorbs duplicate delivery and quarantines payload conflicts" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `price evidence inbox absorbs duplicate delivery and quarantines payload conflicts`() {
        val event = BillingPriceEvidenceEvent(
            eventId = UUID.randomUUID(),
            payloadDigest = "a".repeat(64),
            evidence = BillingPriceEvidence(
                "tenant-a",
                "storage_${UUID.randomUUID()}",
                "USD",
                BigDecimal("0.20"),
                Instant.EPOCH,
            ),
        )

        pricingEvidence.record(event) shouldBeEqualTo BillingPriceEvidenceOutcome.APPLIED
        pricingEvidence.record(event) shouldBeEqualTo BillingPriceEvidenceOutcome.DUPLICATE
        pricingEvidence.record(event.copy(payloadDigest = "b".repeat(64))) shouldBeEqualTo
            BillingPriceEvidenceOutcome.QUARANTINED

        transaction { priceEvidenceInbox.findAll().count() } shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "Billing outbox claim and published mark remain fenced by owner" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Billing outbox claim and published mark remain fenced by owner`() {
        val meterCode = "compute_${UUID.randomUUID()}"

        pricingEvidence.record(
            BillingPriceEvidence(
                tenantId = "tenant-a",
                meterCode = meterCode,
                currency = "USD",
                unitPrice = BigDecimal("0.30"),
                effectiveAt = Instant.EPOCH
            ),
        )
        inbox.handle(
            BillingInboxEvent(
                eventId = UUID.randomUUID(),
                tenantId = "tenant-a",
                aggregateType = "Usage",
                aggregateId = UUID.randomUUID().toString(),
                aggregateVersion = 1,
                payloadDigest = "digest-${UUID.randomUUID()}",
                meterCode = meterCode,
            ),
        ) shouldBeEqualTo BillingInboxOutcome.APPLIED

        val eventId = transaction { outbox.findAll().last().eventId }
        val now = Instant.now()

        outboxJournal.claim("billing-publisher-a", now, 100).any { it.eventId == eventId }.shouldBeTrue()
        outboxJournal.markPublished(eventId, "billing-publisher-b", now).shouldBeFalse()
        outboxJournal.markPublished(eventId, "billing-publisher-a", now).shouldBeTrue()

        transaction {
            outbox.findAll().last { it.eventId == eventId }.status
        } shouldBeEqualTo "PUBLISHED"
    }

/**
 * transaction은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun <T: Any> transaction(crossinline block: () -> T): T =
        TransactionTemplate(transactionManager)
            .execute { block() }.shouldNotBeNull()

}
