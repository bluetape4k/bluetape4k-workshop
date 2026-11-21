package io.bluetape4k.workshop.commerce.usagebilling.invoice.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.invoice.application.InvoiceInboxService
import io.bluetape4k.workshop.commerce.usagebilling.invoice.domain.InvoiceInboxEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.*

@Tag("integration")
@SpringBootTest
/**
 * InvoiceInboxPostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class InvoiceInboxPostgresIntegrationTest {

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
 * inbox은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val inbox: InvoiceInboxService = uninitialized()

    @Autowired
/**
 * lines은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val lines: InvoiceLineRepository = uninitialized()

    @Autowired
/**
 * outbox은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val outbox: InvoiceOutboxRepository = uninitialized()

    @Autowired
/**
 * outboxJournal은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val outboxJournal: ExposedInvoiceOutboxJournal = uninitialized()

    @Autowired
/**
 * transactionManager은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val transactionManager: PlatformTransactionManager = uninitialized()

    @Test
/**
 * 이 시나리오는 "charge replay preserves one original line while adjustment appends a correction line"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `charge replay preserves one original line while adjustment appends a correction line`() {
        val initialCount = transaction { lines.findAll().count() }
        val initialOutboxCount = transaction { outbox.findAll().count() }
        val chargeEventId = UUID.randomUUID()
        val charge = InvoiceInboxEvent(chargeEventId, "ChargeRated", null, BigDecimal("0.10"))

        inbox.handle(charge).created.shouldBeTrue()
        inbox.handle(charge).created.shouldBeFalse()

        val adjustPost = InvoiceInboxEvent(
            eventId = UUID.randomUUID(),
            eventType = "AdjustmentPosted",
            correctionOf = chargeEventId,
            amount = BigDecimal("-0.10")
        )
        inbox.handle(adjustPost).created.shouldBeTrue()

        transaction { lines.findAll().count() } shouldBeEqualTo initialCount + 2
        transaction { requireNotNull(lines.findAll().last().correctionOf) } shouldBeEqualTo chargeEventId
        transaction { outbox.findAll().count() } shouldBeEqualTo initialOutboxCount + 2
        transaction { outbox.findAll().last().eventType } shouldBeEqualTo "InvoiceCorrectionIssued"
    }

    @Test
/**
 * 이 시나리오는 "Invoice outbox claim and published mark remain fenced by owner" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Invoice outbox claim and published mark remain fenced by owner`() {
        val event = InvoiceInboxEvent(UUID.randomUUID(), "ChargeRated", null, BigDecimal("1.00"))
        inbox.handle(event)
        val now = java.time.Instant.parse("2026-07-23T00:00:00Z")

        val lease = outboxJournal.claim("owner-a", now, 1).single()

        outboxJournal.markPublished(lease.eventId, "owner-b", now).shouldBeFalse()
        outboxJournal.markPublished(lease.eventId, "owner-a", now).shouldBeTrue()
    }

/**
 * transaction은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun <T: Any> transaction(crossinline block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }.shouldNotBeNull()
}
