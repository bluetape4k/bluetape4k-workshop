package io.bluetape4k.workshop.commerce.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.order.application.InventoryListenerFailureSwitch
import io.bluetape4k.workshop.commerce.order.application.OrderCommandService
import io.bluetape4k.workshop.commerce.order.application.PaymentEventService
import io.bluetape4k.workshop.commerce.order.domain.OrderStatus
import io.bluetape4k.workshop.commerce.order.domain.PaymentProviderEvent
import io.bluetape4k.workshop.commerce.order.domain.PaymentStatus
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventKind
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import io.bluetape4k.workshop.commerce.order.domain.ReservationStatus
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrder
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrderLine
import io.bluetape4k.workshop.commerce.order.persistence.FulfillmentGroupRepository
import io.bluetape4k.workshop.commerce.order.persistence.InventoryReservationRepository
import io.bluetape4k.workshop.commerce.order.persistence.OrderRepository
import io.bluetape4k.workshop.commerce.order.persistence.PaymentAttemptRepository
import io.bluetape4k.workshop.commerce.order.persistence.ProviderEventInboxRepository
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.utility.Base58
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OrderLifecycleContextRestartIntegrationTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OrderLifecycleContextRestartIntegrationTest {

    companion object: KLogging() {
/**
 * postgres은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

    @Test
/**
 * 이 시나리오는 "state failed publication and provider inbox survive context restart and replay" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `state failed publication and provider inbox survive context restart and replay`() {
        val schema = "restart_${Base58.randomString(12).lowercase()}"
        createSchema(schema)

        try {
            val first = startContext(schema)
            val evidence = try {
                createFailedPublication(first)
            } finally {
                first.close()
            }

            val restarted = startContext(schema)
            try {
                verifyAutomaticReplay(restarted, evidence)
            } finally {
                restarted.close()
            }
        } finally {
            dropSchema(schema)
        }
    }

/**
 * createFailedPublication은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createFailedPublication(context: ConfigurableApplicationContext): RestartEvidence {
        val commands = context.getBean<OrderCommandService>()
        val paymentEvents = context.getBean<PaymentEventService>()
        val payments = context.getBean<PaymentAttemptRepository>()
        val reservations = context.getBean<InventoryReservationRepository>()
        val inbox = context.getBean<ProviderEventInboxRepository>()
        val failureSwitch = context.getBean<InventoryListenerFailureSwitch>()
        val publications = context.getBean<EventPublicationRepository>()
        val submitted = commands.submit(validOrder())

        await atMost 10.seconds withPollInterval 200.milliseconds untilAsserted {
            read(context) {
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.AUTHORIZING
            }
        }

        val providerEventId = "restart-success-${submitted.paymentAttemptId}"
        failureSwitch.failOnce(submitted.orderId)

        val event = PaymentProviderEvent(
            providerEventId = providerEventId,
            paymentAttemptId = submitted.paymentAttemptId,
            kind = ProviderEventKind.SUCCEEDED,
            occurredAt = Instant.parse("2026-07-19T00:00:00Z")
        )
        paymentEvents.ingest(event) shouldBeEqualTo ProviderEventDisposition.APPLIED

        await atMost 10.seconds withPollInterval 200.milliseconds untilAsserted {
            read(context) {
                publications.countByStatus(EventPublication.Status.FAILED) shouldBeGreaterThan 0
                payments.findById(submitted.paymentAttemptId).status shouldBeEqualTo PaymentStatus.SUCCEEDED
                reservations.findByOrderId(submitted.orderId)!!.status shouldBeEqualTo ReservationStatus.HELD
                inbox.find("FAKE", providerEventId)!!.disposition shouldBeEqualTo ProviderEventDisposition.APPLIED
            }
        }
        return RestartEvidence(submitted.orderId, submitted.paymentAttemptId, providerEventId)
    }

/**
 * verifyAutomaticReplay은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun verifyAutomaticReplay(
        context: ConfigurableApplicationContext,
        evidence: RestartEvidence,
    ) {
/**
 * orders은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val orders = context.getBean<OrderRepository>()
/**
 * payments은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payments = context.getBean<PaymentAttemptRepository>()
/**
 * reservations은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val reservations = context.getBean<InventoryReservationRepository>()
/**
 * fulfillments은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fulfillments = context.getBean<FulfillmentGroupRepository>()
/**
 * inbox은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val inbox = context.getBean<ProviderEventInboxRepository>()
/**
 * publications은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val publications = context.getBean<EventPublicationRepository>()

        read(context) {
            payments.findById(evidence.paymentAttemptId).status shouldBeEqualTo PaymentStatus.SUCCEEDED
            inbox.find("FAKE", evidence.providerEventId)!!.disposition shouldBeEqualTo ProviderEventDisposition.APPLIED
        }

        await atMost 10.seconds withPollInterval 200.milliseconds untilAsserted {
            read(context) {
                orders.findById(evidence.orderId).status shouldBeEqualTo OrderStatus.FULFILLMENT_IN_PROGRESS
                reservations.findByOrderId(evidence.orderId)!!.status shouldBeEqualTo ReservationStatus.COMMITTED
                fulfillments.findByOrderId(evidence.orderId).size shouldBeEqualTo 2

                inbox.find("FAKE", evidence.providerEventId)!!
                    .disposition shouldBeEqualTo ProviderEventDisposition.APPLIED

                publications.countByStatus(EventPublication.Status.COMPLETED) shouldBeGreaterThan 0
            }
        }
    }

/**
 * startContext은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun startContext(schema: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(OrderLifecycleApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=${schemaJdbcUrl(schema)}",
                "--spring.datasource.username=${requireNotNull(postgres.username)}",
                "--spring.datasource.password=${requireNotNull(postgres.password)}",
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--spring.datasource.hikari.minimum-idle=0",
                "--spring.main.banner-mode=off"
            )

/**
 * read은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun <T: Any> read(
        context: ConfigurableApplicationContext,
        crossinline block: () -> T,
    ): T {
/**
 * transactions은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val transactions = TransactionTemplate(context.getBean<PlatformTransactionManager>())
        return transactions.execute { block() }.shouldNotBeNull()
    }

/**
 * validOrder은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun validOrder() =
        SubmitOrder(
            tenantId = "tenant-restart",
            customerReference = "restart-order",
            providerMode = ProviderMode.DELAYED_SUCCESS,
            lines = listOf(
                SubmitOrderLine("sku-a", 1, BigDecimal("10.00")),
                SubmitOrderLine("sku-b", 2, BigDecimal("20.00"))
            )
        )

/**
 * createSchema은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createSchema(schema: String) =
        postgresConnection().use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }

/**
 * dropSchema은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun dropSchema(schema: String) =
        postgresConnection().use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }

/**
 * postgresConnection은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun postgresConnection(): Connection =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username.shouldNotBeNull(),
            postgres.password.shouldNotBeNull(),
        )

/**
 * schemaJdbcUrl은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun schemaJdbcUrl(schema: String): String {
        val separator = if ('?' in postgres.jdbcUrl) '&' else '?'
        return "${postgres.jdbcUrl}${separator}currentSchema=$schema"
    }

/**
 * RestartEvidence 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class RestartEvidence(
/**
 * orderId은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val orderId: UUID,
/**
 * paymentAttemptId은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val paymentAttemptId: UUID,
/**
 * providerEventId은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val providerEventId: String,
    )
}
