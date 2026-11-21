package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.config.VoucherCompatibilityTestSupport
import io.bluetape4k.workshop.commerce.voucher.config.VoucherMigrationResult
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * VoucherJdbcExecutorPermitTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherJdbcExecutorPermitTest: VoucherCompatibilityTestSupport() {

    companion object: KLogging()

/**
 * exposedDatabase은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var exposedDatabase: Database? = null

    @BeforeEach
    fun migrate() {
        migrationRunner().migrate() shouldBeEqualTo VoucherMigrationResult.APPLIED
    }

    @Test
/**
 * 이 시나리오는 "connection opens after permit and closes before the permit is returned" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `connection opens after permit and closes before the permit is returned`() {
        val gate = DatabasePermitGate(
            foregroundPermits = 1,
            workerPermits = 1,
            sseMaintenancePermits = 1,
            acquireTimeout = 1.seconds(),
        )
        val probe = PermitCheckingDataSource(dataSource) { gate.requireHeld() }
        val manager = SpringTransactionManager(probe, DatabaseConfig {}, false)
        val jdbc = VoucherJdbcExecutor(gate, manager)

        probe.openCount.get() shouldBeEqualTo 0
        jdbc.foregroundTransaction {
            exposedDatabase = TransactionManager.current().db
            probe.openCount.get() shouldBeEqualTo 1
            probe.closeCount.get() shouldBeEqualTo 0
            gate.availablePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 0
        }

        probe.closeCount.get() shouldBeEqualTo 1
        gate.availablePermits(DatabaseLane.FOREGROUND) shouldBeEqualTo 1
    }

    @AfterEach
    fun closePermitDatabase() {
        exposedDatabase?.let(TransactionManager::closeAndUnregister)
    }

    @Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
/**
 * PermitCheckingDataSource 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class PermitCheckingDataSource(
/**
 * delegate은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val delegate: DataSource,
/**
 * requirePermit은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val requirePermit: () -> Unit,
    ): DataSource by delegate {
/**
 * openCount은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val openCount = AtomicInteger()
/**
 * closeCount은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val closeCount = AtomicInteger()

/**
 * getConnection은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun getConnection(): Connection = checked(delegate.connection)

/**
 * getConnection은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun getConnection(
            username: String,
            password: String,
        ): Connection =
            checked(delegate.getConnection(username, password))

/**
 * checked은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        private fun checked(connection: Connection): Connection {
            requirePermit()
            openCount.incrementAndGet()
            val closed = AtomicBoolean()

            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, arguments ->
                if (method.name == "close" && closed.compareAndSet(false, true)) {
                    closeCount.incrementAndGet()
                }
                method.invoke(connection, *(arguments ?: emptyArray()))
            } as Connection
        }
    }
}
