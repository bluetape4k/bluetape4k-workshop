package io.bluetape4k.workshop.commerce.ticket.highcontention

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.codec.Base58
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.ticket.admission.api.ConsumeGrant
import io.bluetape4k.workshop.commerce.ticket.admission.internal.AdmissionService
import io.bluetape4k.workshop.commerce.ticket.config.TicketMigration
import io.bluetape4k.workshop.commerce.ticket.config.TicketMigrationRunner
import io.bluetape4k.workshop.commerce.ticket.config.TicketProperties
import io.bluetape4k.workshop.commerce.ticket.config.TicketPublicationConfiguration
import io.bluetape4k.workshop.commerce.ticket.config.TicketRedisConfiguration
import io.bluetape4k.workshop.commerce.ticket.config.TicketRedisResources
import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import io.bluetape4k.workshop.commerce.ticket.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.ticket.idempotency.IdempotencyScope
import io.bluetape4k.workshop.commerce.ticket.payment.internal.FakePaymentProvider
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentWorker
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectOperations
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketEffectReceipts
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketInventories
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketOrders
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketPurchaseAttempts
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcExecutor
import io.bluetape4k.workshop.commerce.ticket.purchase.api.AuthorizationRequested
import io.bluetape4k.workshop.commerce.ticket.purchase.api.StartPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseEventPublisher
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseService
import io.bluetape4k.workshop.commerce.ticket.redis.ForegroundLeaseGate
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePolicySnapshot
import io.bluetape4k.workshop.commerce.ticket.salecontrol.internal.SaleService
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.FakeTicketProvider
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.TicketEffectWorker
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.getBean
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

@Suppress("SqlSourceToSinkFlow")
/**
 * TicketHighContentionProfileApplication 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketHighContentionProfileApplication private constructor(
/**
 * applicationContext은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var applicationContext: ConfigurableApplicationContext,
/**
 * schema은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val schema: String,
/**
 * redisUri은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redisUri: String,
/**
 * databasePermitTimeout은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val databasePermitTimeout: Duration,
/**
 * events은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val events: Queue<AuthorizationRequested>,
): AutoCloseable {

/**
 * dataSource은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val dataSource: HikariDataSource
        get() = applicationContext.getBean<DataSource>() as HikariDataSource

/**
 * jdbc은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val jdbc: TicketJdbcExecutor
        get() = applicationContext.getBean<TicketJdbcExecutor>()

/**
 * purchases은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val purchases: PurchaseService
        get() = applicationContext.getBean<PurchaseService>()

/**
 * paymentWorker은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val paymentWorker: PaymentWorker
        get() = applicationContext.getBean<PaymentWorker>()

/**
 * paymentProvider은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val paymentProvider: FakePaymentProvider
        get() = applicationContext.getBean<FakePaymentProvider>()

/**
 * ticketWorker은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val ticketWorker: TicketEffectWorker
        get() = applicationContext.getBean<TicketEffectWorker>()

/**
 * ticketProvider은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val ticketProvider: FakeTicketProvider
        get() = applicationContext.getBean<FakeTicketProvider>()

/**
 * redisResources은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val redisResources: TicketRedisResources
        get() = applicationContext.getBean<TicketRedisResources>()

/**
 * foregroundLeaseGate은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val foregroundLeaseGate: ForegroundLeaseGate
        get() = applicationContext.getBean<ForegroundLeaseGate>()

/**
 * capturedEvents은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun capturedEvents(): List<AuthorizationRequested> = events.toList()

/**
 * createSale은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun createSale(
        namespace: String,
        inventory: Int,
    ): TicketProfileSale {
        namespace.requireNotBlank("namespace")
        require(inventory > 0) { "inventory must be positive" }
/**
 * saleId은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val saleId = stableUuid("$namespace:sale")
/**
 * sharedIp은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val sharedIp = stableUuid("$namespace:shared-ip")
/**
 * now은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val now = Instant.now()

        // HINT: 왜 Exposed SQL DSL 을 쓰지 않고, Raw SQL 을 썼나?
        execute(
            """
            INSERT INTO ticket_sales(sale_id, state, current_policy_version, opens_at, closes_at)
            VALUES ('$saleId', 'open', 1, '${now.minusSeconds(60)}', '${now.plusSeconds(3600)}');
            INSERT INTO ticket_sale_policy_versions(sale_id, policy_version, per_user_limit, max_quantity, hold_seconds)
            VALUES ('$saleId', 1, 4, 4, 30);
            INSERT INTO ticket_inventory(sale_id, grade, total_quantity) VALUES ('$saleId', 'GENERAL', $inventory);
            INSERT INTO ticket_identity_subjects(subject_id, identity_kind) VALUES ('$sharedIp', 'IP');
            """.trimIndent(),
        )
        return TicketProfileSale(namespace, saleId, sharedIp, now)
    }

/**
 * command은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun command(
        sale: TicketProfileSale,
        identityOrdinal: Int,
        attemptOrdinal: Int = 0,
        shareIp: Boolean = false,
    ): StartPurchase {
        identityOrdinal.requireZeroOrPositiveNumber { "identityOrdinal must be non-negative" }
        attemptOrdinal.requireZeroOrPositiveNumber { "attemptOrdinal must be non-negative" }

/**
 * buyer은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val buyer = stableUuid("${sale.namespace}:buyer:$identityOrdinal")
/**
 * ip은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val ip = if (shareIp) sale.sharedIp else stableUuid("${sale.namespace}:ip:$identityOrdinal")
/**
 * attemptId은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val attemptId = stableUuid("${sale.namespace}:attempt:$identityOrdinal:$attemptOrdinal")
/**
 * grantNonce은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val grantNonce = stableUuid("${sale.namespace}:grant:$identityOrdinal:$attemptOrdinal")

        // HINT: 왜 Exposed SQL DSL 을 쓰지 않고, Raw SQL 을 썼나?
        execute(
            """
            INSERT INTO ticket_identity_subjects(subject_id, identity_kind)
            VALUES ('$buyer', 'USER') ON CONFLICT DO NOTHING;
            INSERT INTO ticket_identity_subjects(subject_id, identity_kind)
            VALUES ('$ip', 'IP') ON CONFLICT DO NOTHING;
            INSERT INTO ticket_admission_grants(sale_id, grant_nonce, buyer_subject_id, policy_version, expires_at)
            VALUES ('${sale.saleId}', '$grantNonce', '$buyer', 1, '${sale.now.plusSeconds(300)}');
            """.trimIndent(),
        )
/**
 * owner은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val owner = HttpIdempotencyRepository(jdbc)
            .acquire(
                IdempotencyScope(
                    principalSubjectId = buyer,
                    httpMethod = "POST",
                    canonicalRoute = "/api/v1/sales/{saleId}/purchase-attempts",
                    resourceId = sale.saleId.toString(),
                    operation = "purchase",
                    keyDigest = IdempotencyFingerprint.key(
                        secret = ByteArray(32) { 0x52 },
                        rawKey = "${sale.namespace}:key:$identityOrdinal:$attemptOrdinal",
                    ),
                ),
                IdempotencyFingerprint.request("POST", "/purchase", "{\"grade\":\"GENERAL\",\"quantity\":1}"),
                sale.now,
            ) as IdempotencyDecision.Owner

        return StartPurchase(
            attemptId = attemptId,
            authorizationOperationId = stableUuid("${sale.namespace}:payment:$identityOrdinal:$attemptOrdinal"),
            idempotencyOwnerId = owner.id,
            buyerSubjectId = buyer,
            ipSubjectId = ip,
            grade = "GENERAL",
            quantity = 1,
            grant = ConsumeGrant(sale.saleId, grantNonce, buyer, 1, attemptId),
            policy = SalePolicySnapshot(
                saleId = sale.saleId,
                state = SaleState.OPEN,
                policyVersion = 1,
                opensAt = sale.now.minusSeconds(60),
                closesAt = sale.now.plusSeconds(3600),
            ),
        )
    }

/**
 * execute은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

/**
 * committedInventoryQuantity은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun committedInventoryQuantity(saleId: UUID): Long = jdbc.transaction {
        TicketInventories
            .select(TicketInventories.heldQuantity, TicketInventories.soldQuantity)
            .where { TicketInventories.saleId eq saleId }
            .single()
            .let { row ->
                (row[TicketInventories.heldQuantity] + row[TicketInventories.soldQuantity]).toLong()
            }
    }

/**
 * purchaseAttemptState은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun purchaseAttemptState(attemptId: UUID): String = jdbc.transaction {
        TicketPurchaseAttempts
            .select(TicketPurchaseAttempts.state)
            .where { TicketPurchaseAttempts.id eq attemptId }
            .single()[TicketPurchaseAttempts.state]
    }

/**
 * issueEffectOperationId은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun issueEffectOperationId(attemptId: UUID): UUID = jdbc.transaction {
/**
 * orderId은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val orderId = TicketOrders
            .select(TicketOrders.id)
            .where { TicketOrders.attemptId eq attemptId }
            .single()[TicketOrders.id].value
        TicketEffectOperations
            .select(TicketEffectOperations.operationId)
            .where {
                (TicketEffectOperations.effectKind eq "issue") and
                        (TicketEffectOperations.orderId eq orderId)
            }
            .single()[TicketEffectOperations.operationId]
    }

/**
 * effectReceiptCount은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun effectReceiptCount(operationId: UUID): Long = jdbc.transaction {
        TicketEffectReceipts
            .selectAll()
            .where { TicketEffectReceipts.operationId eq operationId }
            .count()
    }

/**
 * purchaseAttemptCount은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun purchaseAttemptCount(): Long = jdbc.transaction { TicketPurchaseAttempts.selectAll().count() }

/**
 * effectOperationCount은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun effectOperationCount(): Long = jdbc.transaction { TicketEffectOperations.selectAll().count() }

/**
 * effectReceiptCount은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun effectReceiptCount(): Long = jdbc.transaction { TicketEffectReceipts.selectAll().count() }

/**
 * duplicateEffectReceiptCount은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun duplicateEffectReceiptCount(): Long = jdbc.transaction {
        TicketEffectReceipts
            .select(TicketEffectReceipts.operationId)
            .map { it[TicketEffectReceipts.operationId] }
            .groupingBy { it }
            .eachCount()
            .count { (_, count) -> count > 1 }
            .toLong()
    }

/**
 * restart은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun restart() {
        applicationContext.close()
        applicationContext = startContext(schema, redisUri, databasePermitTimeout, events)
    }

/**
 * close은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        try {
            applicationContext.close()
        } finally {
            dropSchema(schema)
        }
    }

    internal companion object: KLogging() {

/**
 * postgres은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

/**
 * start은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun start(
            redisUri: String,
            databasePermitTimeout: Duration = 250.millis(),
        ): TicketHighContentionProfileApplication {
/**
 * validRedisUri은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val validRedisUri = redisUri.requireNotBlank("redisUri")
/**
 * validDatabasePermitTimeout은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val validDatabasePermitTimeout =
                databasePermitTimeout.requirePositiveDuration("databasePermitTimeout")
/**
 * schema은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val schema = "ticket_hc_${Base58.randomString(8).lowercase()}"
            adminConnection().use { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            }
/**
 * events은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val events = ConcurrentLinkedQueue<AuthorizationRequested>()
            return try {
                TicketHighContentionProfileApplication(
                    applicationContext = startContext(
                        schema,
                        validRedisUri,
                        validDatabasePermitTimeout,
                        events,
                    ),
                    schema = schema,
                    redisUri = validRedisUri,
                    databasePermitTimeout = validDatabasePermitTimeout,
                    events = events,
                )
            } catch (error: Throwable) {
                dropSchema(schema)
                throw error
            }
        }

/**
 * startContext은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun startContext(
            schema: String,
            redisUri: String,
            databasePermitTimeout: Duration,
            events: Queue<AuthorizationRequested>,
        ): ConfigurableApplicationContext =
            SpringApplicationBuilder(ProfileSpringApplication::class.java)
                .web(WebApplicationType.NONE)
                .initializers({ context ->
                    context.beanFactory.registerSingleton(PROFILE_EVENTS_BEAN, events)
                })
                .run(
                    "--spring.datasource.url=${schemaJdbcUrl(schema)}",
                    "--spring.datasource.username=${postgres.username ?: PostgreSQLServer.USERNAME}",
                    "--spring.datasource.password=${postgres.password ?: PostgreSQLServer.PASSWORD}",
                    "--spring.datasource.hikari.maximum-pool-size=20",
                    "--spring.datasource.hikari.schema=$schema",
                    "--spring.main.banner-mode=off",
                    "--workshop.ticket.db.permit-timeout=${databasePermitTimeout.toMillis()}ms",
                    "--workshop.ticket.redis.uri=$redisUri",
                )

/**
 * schemaJdbcUrl은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun schemaJdbcUrl(schema: String): String =
            postgres.jdbcUrl + if ('?' in postgres.jdbcUrl) "&currentSchema=$schema" else "?currentSchema=$schema"

/**
 * dropSchema은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun dropSchema(schema: String) {
            adminConnection().use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }

/**
 * adminConnection은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun adminConnection() =
            DriverManager.getConnection(
                postgres.jdbcUrl,
                postgres.username ?: PostgreSQLServer.USERNAME,
                postgres.password ?: PostgreSQLServer.PASSWORD,
            )

/**
 * stableUuid은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun stableUuid(value: String): UUID =
            UUID.nameUUIDFromBytes(value.toUtf8Bytes())

/**
 * PROFILE_EVENTS_BEAN은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val PROFILE_EVENTS_BEAN = "ticketHighContentionCapturedEvents"
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TicketProperties::class)
    @ImportAutoConfiguration(DataSourceAutoConfiguration::class)
    @Import(TicketRedisConfiguration::class, ProfileOverrides::class)
/**
 * ProfileSpringApplication 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    internal class ProfileSpringApplication

    @TestConfiguration(proxyBeanMethods = false)
/**
 * ProfileOverrides 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    internal class ProfileOverrides {
/**
 * production은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val production = TicketPublicationConfiguration()

        @Bean
/**
 * ticketHighContentionMigration은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun ticketHighContentionMigration(dataSource: DataSource): SmartInitializingSingleton =
            SmartInitializingSingleton {
                TicketMigrationRunner(
                    dataSource = dataSource,
                    migration = TicketMigration(
                        "001",
                        ClassPathResource("db/migration/V001__concert_ticket_flash_sale.sql"),
                    ),
                    advisoryLockKey = 521_522L,
                ).migrate()
            }

        @Bean
/**
 * ticketClock은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun ticketClock(): Clock = production.ticketClock()

        @Bean
/**
 * ticketJdbcExecutor은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun ticketJdbcExecutor(dataSource: DataSource, properties: TicketProperties): TicketJdbcExecutor =
            production.ticketJdbcExecutor(dataSource, properties)

        @Bean
/**
 * ticketSaleService은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun ticketSaleService(): SaleService = production.ticketSaleService()

        @Bean
/**
 * ticketAdmissionService은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun ticketAdmissionService(jdbc: TicketJdbcExecutor, clock: Clock): AdmissionService =
            production.ticketAdmissionService(jdbc, clock)

        @Bean
        @Primary
/**
 * highContentionPurchaseEventPublisher은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun highContentionPurchaseEventPublisher(
            ticketHighContentionCapturedEvents: MutableList<AuthorizationRequested>,
        ): PurchaseEventPublisher =
            PurchaseEventPublisher { event ->
                ticketHighContentionCapturedEvents += event
            }

        @Bean
/**
 * purchaseService은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun purchaseService(
            jdbc: TicketJdbcExecutor,
            sale: SaleService,
            admission: AdmissionService,
            clock: Clock,
            events: PurchaseEventPublisher,
        ): PurchaseService =
            production.purchaseService(jdbc, sale, admission, clock, events)

        @Bean
/**
 * paymentProvider은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun paymentProvider(): FakePaymentProvider = production.paymentProvider()

        @Bean
/**
 * paymentWorker은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun paymentWorker(
            jdbc: TicketJdbcExecutor,
            purchases: PurchaseService,
            provider: FakePaymentProvider,
        ): PaymentWorker =
            production.paymentWorker(jdbc, purchases, provider)

        @Bean
/**
 * ticketProvider은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun ticketProvider(): FakeTicketProvider = production.ticketProvider()

        @Bean
/**
 * ticketEffectWorker은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun ticketEffectWorker(
            jdbc: TicketJdbcExecutor,
            purchases: PurchaseService,
            provider: FakeTicketProvider,
        ): TicketEffectWorker =
            production.ticketEffectWorker(jdbc, purchases, provider)
    }
}

/**
 * TicketProfileSale 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal data class TicketProfileSale(
/**
 * namespace은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val namespace: String,
/**
 * saleId은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val saleId: UUID,
/**
 * sharedIp은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val sharedIp: UUID,
/**
 * now은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val now: Instant,
)
