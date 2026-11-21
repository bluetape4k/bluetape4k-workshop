package io.bluetape4k.workshop.commerce.ticket.persistence

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getDataSource
import io.bluetape4k.workshop.commerce.ticket.config.TicketMigration
import io.bluetape4k.workshop.commerce.ticket.config.TicketMigrationRunner
import io.bluetape4k.workshop.commerce.ticket.domain.PurchaseState
import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import org.springframework.core.io.ClassPathResource
import java.sql.DriverManager
import java.time.Instant
import java.util.*

/**
 * TicketDatabaseFixture 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketDatabaseFixture: AutoCloseable {

    companion object: KLogging() {
/**
 * postgres은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

/**
 * MAXIMUM_POOL_SIZE은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val MAXIMUM_POOL_SIZE = 8
    }

/**
 * schema은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val schema = "ticket_repository_${Base58.randomString(8).lowercase()}"
/**
 * dataSource은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val dataSource: HikariDataSource
/**
 * executor은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val executor: TicketJdbcExecutor

    init {
        adminConnection().use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        dataSource = postgres.getDataSource {
            schema = this@TicketDatabaseFixture.schema
            maximumPoolSize = MAXIMUM_POOL_SIZE
            poolName = "ticket-test-$schema"
        }
        TicketMigrationRunner(
            dataSource = dataSource,
            migration = TicketMigration("001", ClassPathResource("db/migration/V001__concert_ticket_flash_sale.sql")),
            advisoryLockKey = 521_101L,
        ).migrate()
        executor = TicketJdbcExecutor(dataSource, foregroundPermits = 2)
    }

/**
 * seedAuthority은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun seedAuthority(
        saleId: UUID,
        userSubjectId: UUID,
        ipSubjectId: UUID,
        firstAttemptId: UUID,
        secondAttemptId: UUID,
    ) {
/**
 * now은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val now = Instant.now()

        executor.transaction {
            TicketSaleEntity.new(saleId) {
                state = SaleState.OPEN.code
                currentPolicyVersion = 1L
                opensAt = now.minusSeconds(60)
                closesAt = now.plusSeconds(3_600)
                revision = 0L
                createdAt = now
                updatedAt = now
            }
            TicketSalePolicyEntity.new {
                this.saleId = saleId
                policyVersion = 1L
                perUserLimit = 1
                maxQuantity = 4
                holdSeconds = 30L
                createdAt = now
            }
            TicketInventoryEntity.new {
                this.saleId = saleId
                grade = "GENERAL"
                totalQuantity = 10
                heldQuantity = 0
                soldQuantity = 0
                revision = 0L
            }
            TicketIdentitySubjectEntity.new(userSubjectId) {
                identityKind = IdentityKind.USER.name
                createdAt = now
                anonymizedAt = null
            }
            TicketIdentitySubjectEntity.new(ipSubjectId) {
                identityKind = IdentityKind.IP.name
                createdAt = now
                anonymizedAt = null
            }
            listOf(firstAttemptId, secondAttemptId).forEach { attemptId ->
                TicketPurchaseAttemptEntity.new(attemptId) {
                    this.saleId = saleId
                    this.userSubjectId = userSubjectId
                    this.ipSubjectId = ipSubjectId
                    grade = "GENERAL"
                    quantity = 1
                    policyVersion = 1L
                    state = PurchaseState.INVENTORY_HELD.code
                    holdDeadline = now.plusSeconds(30)
                    authorizationOperationId = Uuid.V7.nextId()
                    revision = 0L
                    createdAt = now
                    updatedAt = now
                }
            }
        }
    }

/**
 * seedSale은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun seedSale(
        saleId: UUID,
        totalQuantity: Int? = null,
    ) {
/**
 * now은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val now = Instant.now()

        executor.transaction {
            TicketSaleEntity.new(saleId) {
                state = SaleState.OPEN.code
                currentPolicyVersion = 1L
                opensAt = now
                closesAt = now.plusSeconds(3_600)
                revision = 0L
                createdAt = now
                updatedAt = now
            }
            totalQuantity?.let { quantity ->
                TicketInventoryEntity.new {
                    this.saleId = saleId
                    this.grade = "GENERAL"
                    this.totalQuantity = quantity
                    this.heldQuantity = 0
                    this.soldQuantity = 0
                    this.revision = 0L
                }
            }
        }
    }

/**
 * seedIdentitySubject은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun seedIdentitySubject(
        subjectId: UUID,
        identityKind: IdentityKind = IdentityKind.USER,
    ) {
/**
 * now은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val now = Instant.now()
        executor.transaction {
            TicketIdentitySubjectEntity.new(subjectId) {
                this.identityKind = identityKind.name
                this.createdAt = now
                this.anonymizedAt = null
            }
        }
    }

/**
 * execute은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(sql.requireNotBlank("sql"))
            }
        }
    }

/**
 * close은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun close() {
        try {
            executor.close()
            dataSource.close()
        } finally {
            adminConnection().use { connection ->
                connection.createStatement().use { stmt ->
                    stmt.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
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
}
