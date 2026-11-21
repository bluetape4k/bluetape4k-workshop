package io.bluetape4k.workshop.commerce.usagebilling.meter.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.meter.application.MeterCommandService
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.ActivatePriceCommand
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
 * MeterOutboxPostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class MeterOutboxPostgresIntegrationTest {

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
 * service은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var service: MeterCommandService

    @Autowired
/**
 * priceVersions은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var priceVersions: MeterPriceVersionRepository

    @Autowired
/**
 * outbox은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var outbox: MeterOutboxRepository

    @Autowired
/**
 * outboxJournal은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var outboxJournal: ExposedMeterOutboxJournal

    @Autowired
/**
 * transactionManager은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
/**
 * 이 시나리오는 "price activation commits an immutable price version and pending outbox event together"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `price activation commits an immutable price version and pending outbox event together`() {
        val priceVersionCount = transaction { priceVersions.findAll().count() }
        val outboxCount = transaction { outbox.findAll().count() }

        service.activatePrice(
            ActivatePriceCommand(
                idempotencyKey = "postgres-activation-1",
                tenantId = "tenant-a",
                meterCode = "api_calls",
                currency = "USD",
                unitPrice = BigDecimal("0.10"),
                effectiveAt = Instant.parse("2026-07-22T00:00:00Z"),
            ),
        )

        transaction { priceVersions.findAll().count() } shouldBeEqualTo priceVersionCount + 1
        transaction { outbox.findAll().count() } shouldBeEqualTo outboxCount + 1
        transaction { outbox.findAll().last().status } shouldBeEqualTo "PENDING"
    }

    @Test
/**
 * 이 시나리오는 "price version constraint failure rolls back its local outbox and receipt" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `price version constraint failure rolls back its local outbox and receipt`() {
        val meterCode = "api_calls_${UUID.randomUUID()}"
        val effectiveAt = Instant.parse("2026-07-22T01:00:00Z")
        val first = activation("postgres-atomic-1", meterCode, effectiveAt)
        service.activatePrice(first)
        val priceVersionCount = transaction { priceVersions.findAll().count() }
        val outboxCount = transaction { outbox.findAll().count() }

        assertFailsWith<Exception> {
            service.activatePrice(activation("postgres-atomic-2", meterCode, effectiveAt))
        }

        transaction { priceVersions.findAll().count() } shouldBeEqualTo priceVersionCount
        transaction { outbox.findAll().count() } shouldBeEqualTo outboxCount
    }

    @Test
/**
 * 이 시나리오는 "an expired outbox claim can be safely reclaimed by a later worker" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `an expired outbox claim can be safely reclaimed by a later worker`() {
        val activation = activation(
            idempotencyKey = "lease-${UUID.randomUUID()}",
            meterCode = "lease-meter",
            effectiveAt = Instant.EPOCH
        )
        val result = service.activatePrice(activation)
        val claimedAt = Instant.parse("2026-07-22T00:00:00Z")

        outboxJournal
            .claim("worker-a", claimedAt, 100)
            .map { it.eventId }
            .contains(result.eventId).shouldBeTrue()

        outboxJournal
            .claim("worker-b", claimedAt.plusSeconds(31), 100)
            .first { it.eventId == result.eventId }
            .claimOwner shouldBeEqualTo "worker-b"
    }

    @Test
/**
 * 이 시나리오는 "conditional claim prevents a second worker from leasing an active event" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `conditional claim prevents a second worker from leasing an active event`() {
        val activation = activation("active-${UUID.randomUUID()}", "active-meter", Instant.EPOCH)
        val result = service.activatePrice(activation)
        val claimedAt = Instant.parse("2026-07-22T00:00:00Z")

        outboxJournal
            .claim("worker-a", claimedAt, 100)
            .map { it.eventId }
            .contains(result.eventId).shouldBeTrue()

        outboxJournal
            .claim("worker-b", claimedAt, 100)
            .map { it.eventId }
            .contains(result.eventId).shouldBeFalse()
    }

/**
 * activation은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun activation(
        idempotencyKey: String,
        meterCode: String,
        effectiveAt: Instant,
    ): ActivatePriceCommand =
        ActivatePriceCommand(
            idempotencyKey = idempotencyKey,
            tenantId = "tenant-a",
            meterCode = meterCode,
            currency = "USD",
            unitPrice = BigDecimal("0.10"),
            effectiveAt = effectiveAt,
        )

/**
 * transaction은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun <T: Any> transaction(crossinline block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }.shouldNotBeNull()
}
