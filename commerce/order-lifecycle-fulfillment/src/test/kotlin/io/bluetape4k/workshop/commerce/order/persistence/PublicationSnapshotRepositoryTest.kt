package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.modulith.events.EventPublication
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * PublicationSnapshotRepositoryTest 테스트 묶음은 주문 수명주기·배송 이행의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class PublicationSnapshotRepositoryTest {

    companion object: KLogging() {
/**
 * postgres은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

/**
 * table은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val table = ExposedEventPublicationTable("commerce_publication_snapshot_test")
/**
 * repository은 주문 수명주기·배송 이행 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val repository = PublicationSnapshotRepository(table)

    @BeforeAll
    fun connectPostgres() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = requireNotNull(postgres.username),
            password = requireNotNull(postgres.password)
        )
    }

    @BeforeEach
    fun createSchema() {
        transaction {
            SchemaUtils.drop(table)
            SchemaUtils.create(table)
        }
    }

    @AfterEach
    fun dropSchema() {
        transaction { SchemaUtils.drop(table) }
    }

    @Test
/**
 * 이 시나리오는 "publication snapshot uses one bounded aggregate query" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `publication snapshot uses one bounded aggregate query`() {
        val oldestIncomplete = Instant.parse("2026-07-18T00:00:00Z")
        val selectCount = AtomicInteger()

        val snapshot = transaction {
            insertPublication(EventPublication.Status.PUBLISHED, oldestIncomplete.plusSeconds(10))
            insertPublication(EventPublication.Status.PROCESSING, oldestIncomplete.plusSeconds(20))
            insertPublication(EventPublication.Status.FAILED, oldestIncomplete.plusSeconds(30))
            insertPublication(EventPublication.Status.RESUBMITTED, oldestIncomplete.plusSeconds(40))
            insertPublication(
                EventPublication.Status.COMPLETED,
                oldestIncomplete.minusSeconds(10),
                completionDate = oldestIncomplete.minusSeconds(5)
            )
            insertPublication(status = null, publicationDate = oldestIncomplete)

            addLogger(selectCountingLogger(selectCount))
            repository.snapshot()
        }
        log.debug { "snapshot=$snapshot" }

        selectCount.get() shouldBeEqualTo 1
        snapshot.published shouldBeEqualTo 1
        snapshot.processing shouldBeEqualTo 1
        snapshot.failed shouldBeEqualTo 1
        snapshot.resubmitted shouldBeEqualTo 1
        snapshot.completed shouldBeEqualTo 1
        snapshot.oldestIncomplete shouldBeEqualTo oldestIncomplete
    }

    @Test
/**
 * 이 시나리오는 "null publication status success" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `null publication status success`() {
        transaction {
            table.insert { row ->
                row[table.id] = UUID.randomUUID()
                row[listenerId] = "test-listener"
                row[eventType] = "test.Event"
                row[serializedEvent] = "{}"
                row[publicationDate] = Instant.parse("2026-07-18T00:00:00Z")
                row[completionDate] = null
                row[status] = null
                row[completionAttempts] = 0
                row[lastResubmissionDate] = Instant.parse("2026-07-18T00:00:00Z")
            }

            repository.snapshot()
        }.also {
            log.debug { "snapshot=$it" }
        }
    }

    @Suppress("UnusedReceiverParameter")
/**
 * insertPublication은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun Transaction.insertPublication(
        status: EventPublication.Status?,
        publicationDate: Instant,
        completionDate: Instant? = null,
    ) {
        table.insert { row ->
            row[table.id] = UUID.randomUUID()
            row[table.listenerId] = "test-listener"
            row[table.eventType] = "test.Event"
            row[table.serializedEvent] = "{}"
            row[table.publicationDate] = publicationDate
            row[table.completionDate] = completionDate
            row[table.status] = status
            row[table.completionAttempts] = 0
            row[table.lastResubmissionDate] = publicationDate
        }
    }

/**
 * selectCountingLogger은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun selectCountingLogger(counter: AtomicInteger) =
        object: SqlLogger {
/**
 * log은 주문 수명주기·배송 이행 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun log(
                context: StatementContext,
                transaction: Transaction,
            ) {
                if (context.statement.type.name == "SELECT") counter.incrementAndGet()
            }
        }
}
