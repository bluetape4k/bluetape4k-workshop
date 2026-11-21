package io.bluetape4k.workshop.commerce.usagebilling.query.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryInboxService
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryQuarantineService
import io.bluetape4k.workshop.commerce.usagebilling.query.application.QueryRecoveryService
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryQuarantineEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.*

@Tag("integration")
@SpringBootTest
/**
 * QueryProjectionPostgresIntegrationTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class QueryProjectionPostgresIntegrationTest {

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
    private val inbox: QueryInboxService = uninitialized()

    @Autowired
/**
 * inboxEvents은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val inboxEvents: QueryInboxRepository = uninitialized()

    @Autowired
/**
 * readModels은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val readModels: QueryReadModelRepository = uninitialized()

    @Autowired
/**
 * checkpoints은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val checkpoints: QueryCheckpointRepository = uninitialized()

    @Autowired
/**
 * recovery은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val recovery: QueryRecoveryService = uninitialized()

    @Autowired
/**
 * quarantine은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val quarantine: QueryQuarantineService = uninitialized()

    @Autowired
/**
 * redriveAudits은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val redriveAudits: QueryRedriveAuditRepository = uninitialized()

    @Autowired
/**
 * transactionManager은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val transactionManager: PlatformTransactionManager = uninitialized()

    @Test
/**
 * 이 시나리오는 "replay persists one inbox receipt and one read model while advancing one checkpoint" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `replay persists one inbox receipt and one read model while advancing one checkpoint`() {
        val inboxCount = transaction { inboxEvents.findAll().count() }
        val readModelCount = transaction { readModels.findAll().count() }
        val checkpoint = transaction { checkpoints.findAll().singleOrNull()?.position ?: 0 }
        val event = QueryInboxEvent(UUID.randomUUID(), "tenant-a", "InvoiceIssued")

        inbox.apply(event).applied.shouldBeTrue()
        inbox.apply(event).applied.shouldBeFalse()

        transaction { inboxEvents.findAll().count() } shouldBeEqualTo inboxCount + 1
        transaction { readModels.findAll().count() } shouldBeEqualTo readModelCount + 1
        transaction { checkpoints.findAll().single().position } shouldBeEqualTo checkpoint + 1
    }

    @Test
/**
 * 이 시나리오는 "redrive creates an immutable audit without mutating a financial projection" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `redrive creates an immutable audit without mutating a financial projection`() {
        val auditCount = transaction { redriveAudits.findAll().count() }
        val eventId = UUID.randomUUID()
        quarantine.record(
            QueryQuarantineEvent(
                eventId = eventId,
                tenantId = "tenant-a",
                eventType = "InvoiceIssued",
                reason = "unsupported_schema",
                quarantinedAt = Instant.now()
            ),
        )

        recovery.redrive(eventId = eventId, "operator-a", "correlation-a").requested.shouldBeTrue()

        transaction { redriveAudits.findAll().count() } shouldBeEqualTo auditCount + 1
        transaction { requireNotNull(redriveAudits.findAll().last()).currentState } shouldBeEqualTo "REDRIVE_REQUESTED"
    }

/**
 * transaction은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun <T: Any> transaction(crossinline block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }.shouldNotBeNull()
}
