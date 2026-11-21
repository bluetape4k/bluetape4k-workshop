package io.bluetape4k.workshop.commerce.usagebilling.usage.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.usage.application.PriceEvidenceService
import io.bluetape4k.workshop.commerce.usagebilling.usage.application.UsageCommandService
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.AcceptUsageCommand
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.PriceEvidenceInboxOutcome
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceResult
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
 * UsageAcceptancePostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class UsageAcceptancePostgresIntegrationTest {

    private companion object: KLogging() {
/**
 * NOW은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val NOW: Instant = Instant.parse("2026-07-22T00:00:00Z")
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
 * priceEvidence은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var priceEvidence: PriceEvidenceService

    @Autowired
/**
 * usage은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var usage: UsageCommandService

    @Autowired
/**
 * usageRecords은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var usageRecords: UsageRecordRepository

    @Autowired
/**
 * outbox은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var outbox: UsageOutboxRepository

    @Autowired
/**
 * priceEvidenceInbox은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var priceEvidenceInbox: UsagePriceEvidenceInboxRepository

    @Autowired
/**
 * outboxJournal은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var outboxJournal: ExposedUsageOutboxJournal

    @Autowired
/**
 * transactionManager은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
/**
 * 이 시나리오는 "persisted price evidence accepts one source event and replays its duplicate without a
 * second outbox row" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `persisted price evidence accepts one source event and replays its duplicate without a second outbox row`() {
        val meterCode = "api_calls_${UUID.randomUUID()}"
        val evidence = PriceEvidence("tenant-a", meterCode, "USD", BigDecimal("0.10"), NOW)
        priceEvidence.record(evidence)

        val usageCount = transaction { usageRecords.findAll().count() }
        val outboxCount = transaction { outbox.findAll().count() }
        val command = AcceptUsageCommand(
            tenantId = "tenant-a",
            sourceSystem = "meter-agent",
            sourceEventId = "source_${UUID.randomUUID()}",
            meterCode = meterCode,
            currency = "USD",
            quantity = BigDecimal.ONE,
            occurredAt = NOW,
        )

        usage.accept(command).replayed.shouldBeFalse()
        usage.accept(command).replayed.shouldBeTrue()

        transaction { usageRecords.findAll().count() } shouldBeEqualTo usageCount + 1
        transaction { outbox.findAll().count() } shouldBeEqualTo outboxCount + 1
        transaction { outbox.findAll().last().status } shouldBeEqualTo "PENDING"
    }

    @Test
/**
 * 이 시나리오는 "price evidence inbox atomically absorbs duplicate delivery and quarantines digest
 * conflicts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `price evidence inbox atomically absorbs duplicate delivery and quarantines digest conflicts`() {
        val meterCode = "api_calls_${UUID.randomUUID()}"
        val eventId = UUID.randomUUID()
        val event = PriceEvidenceInboxEvent(
            eventId = eventId,
            tenantId = "tenant-a",
            payloadDigest = "a".repeat(64),
            evidence = PriceEvidence("tenant-a", meterCode, "USD", BigDecimal("0.10"), NOW),
        )
        log.debug { "event=$event" }

        priceEvidence.record(event) shouldBeEqualTo PriceEvidenceInboxOutcome.APPLIED
        priceEvidence.record(event) shouldBeEqualTo PriceEvidenceInboxOutcome.DUPLICATE
        priceEvidence.record(event.copy(payloadDigest = "b".repeat(64))) shouldBeEqualTo
                PriceEvidenceInboxOutcome.QUARANTINED

        transaction { priceEvidenceInbox.findAll().count() } shouldBeEqualTo 1
    }

    @Test
/**
 * 이 시나리오는 "an expired Usage outbox claim can be reclaimed while an active claim cannot" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `an expired Usage outbox claim can be reclaimed while an active claim cannot`() {
        val meterCode = "api_calls_${UUID.randomUUID()}"
        val evidence = PriceEvidence("tenant-a", meterCode, "USD", BigDecimal("0.10"), NOW)
        priceEvidence.record(evidence)

        val result: UsageAcceptanceResult = usage.accept(
            AcceptUsageCommand(
                tenantId = "tenant-a",
                sourceSystem = "meter-agent",
                sourceEventId = "source_${UUID.randomUUID()}",
                meterCode = meterCode,
                currency = "USD",
                quantity = BigDecimal.ONE,
                occurredAt = NOW,
            ),
        )
        log.debug { "result=$result" }

        outboxJournal.claim("worker-a", NOW, 100)
            .map { it.eventId }
            .contains(result.eventId).shouldBeTrue()

        outboxJournal.claim("worker-b", NOW, 100)
            .map { it.eventId }
            .contains(result.eventId).shouldBeFalse()

        outboxJournal.claim("worker-b", NOW + 31.seconds(), 100)
            .map { it.eventId }
            .contains(result.eventId).shouldBeTrue()
    }

/**
 * transaction은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun <T: Any> transaction(block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }.shouldNotBeNull()

}
