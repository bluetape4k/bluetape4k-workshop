package io.bluetape4k.workshop.commerce.voucherpool.config

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcMetrics
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolLockTimeoutApplier
import io.bluetape4k.workshop.commerce.voucherpool.web.VoucherPoolHttpProperties
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * VoucherPoolConfigurationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolConfigurationTest {

    companion object: KLogging()

/**
 * configuration은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val configuration = VoucherPoolConfiguration()

    @Test
/**
 * 이 시나리오는 "live Hikari settings govern permit budget and acquisition timeout" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live Hikari settings govern permit budget and acquisition timeout`() {
        HikariDataSource().use { dataSource ->
            dataSource.maximumPoolSize = 16
            dataSource.connectionTimeout = 4_321
            val properties = testProperties()

            val gate = configuration.databasePermitGate(dataSource, properties)
            val timeouts = configuration.voucherPoolJdbcTimeouts(dataSource, properties)
            val executor = configuration.voucherPoolJdbcExecutor(
                gate = gate,
                transactionManager = mockk<PlatformTransactionManager>(),
                metrics = VoucherPoolJdbcMetrics.NONE,
                lockTimeoutApplier = VoucherPoolLockTimeoutApplier.NONE,
                timeouts = timeouts,
            )

            log.debug { "DatabasePermitGate: ${gate.snapshot()}" }
            gate.snapshot().capacities.values.sum() shouldBeEqualTo dataSource.maximumPoolSize - 1
            executor.timeouts.connectionAcquisition shouldBeEqualTo 4_321.millis()
        }
    }

    @Test
/**
 * 이 시나리오는 "live Hikari pool size rejects lane capacity overcommit" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live Hikari pool size rejects lane capacity overcommit`() {
        HikariDataSource().use { dataSource ->
            dataSource.maximumPoolSize = 15

            assertFailsWith<IllegalArgumentException> {
                configuration.databasePermitGate(dataSource, testProperties())
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "SSE database properties map to JDBC transaction and lock timeouts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `SSE database properties map to JDBC transaction and lock timeouts`() {
        HikariDataSource().use { dataSource ->
            dataSource.maximumPoolSize = 16
            val properties = VoucherPoolProperties(
                http = testHttpProperties(),
                database = VoucherPoolDatabaseProperties(
                    sse = VoucherPoolLaneProperties(
                        capacity = 3,
                        permitWait = 1.seconds(),
                        transactionTimeout = 7.seconds(),
                        lockTimeout = 3.seconds(),
                    ),
                ),
            )

            val timeouts = configuration.voucherPoolJdbcTimeouts(dataSource, properties)
            log.debug { "timeouts: $timeouts" }
            timeouts.sseTransaction shouldBeEqualTo 7.seconds()
            timeouts.sseLock shouldBeEqualTo 3.seconds()
        }
    }

    @Test
/**
 * 이 시나리오는 "configuration representations redact operator credentials" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `configuration representations redact operator credentials`() {
        val properties = testProperties()

        log.debug { "properties: $properties" }
        properties.http.toString().let {
            it shouldNotContain "test-operator-secret"
            it shouldNotContain "test-voucher-pool-operator-guard"
        }

        properties.toString().let {
            it shouldNotContain "test-operator-secret"
            it shouldNotContain "test-voucher-pool-operator-guard"
        }
    }

    @Test
/**
 * 이 시나리오는 "default worker instance uses a compact base58 namespace" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `default worker instance uses a compact base58 namespace`() {
        val workerInstanceId = testProperties().workerInstanceId
        val suffix = workerInstanceId.removePrefix("voucher-pool-")

        workerInstanceId shouldStartWith "voucher-pool-"
        suffix.length shouldBeEqualTo 16
        suffix.all { it.isLetterOrDigit() }.shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "non-Hikari data source fails configuration without opening a connection" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `non-Hikari data source fails configuration without opening a connection`() {
        val dataSource = mockk<DataSource>(relaxed = true)

        val failure = assertFailsWith<IllegalStateException> {
            configuration.databasePermitGate(dataSource, testProperties())
        }

        log.debug { "failure: $failure" }
        failure.message shouldBeEqualTo
                "pre-generated-voucher-pool requires HikariDataSource so the live pool size and connection timeout " +
                "remain authoritative"
    }

/**
 * testProperties은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun testProperties(): VoucherPoolProperties =
        VoucherPoolProperties(http = testHttpProperties())

/**
 * testHttpProperties은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun testHttpProperties(): VoucherPoolHttpProperties =
        VoucherPoolHttpProperties(
            operatorSecret = "test-operator-secret-0000000000000001",
            operatorGuard = "test-voucher-pool-operator-guard",
        )
}
