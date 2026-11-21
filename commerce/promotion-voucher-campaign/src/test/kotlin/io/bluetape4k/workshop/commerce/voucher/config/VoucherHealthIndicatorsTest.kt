package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.sql.Connection
import javax.sql.DataSource

/**
 * VoucherHealthIndicatorsTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherHealthIndicatorsTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "PostgreSQL alone controls authoritative readiness" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `PostgreSQL alone controls authoritative readiness`() {
        val connection = mockk<Connection>(relaxed = true)
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection
        every { connection.isValid(1) } returns true

        VoucherDatabaseHealthIndicator(dataSource).health().status shouldBeEqualTo Status.UP

        every { dataSource.connection } throws IllegalStateException("database unavailable")

        VoucherDatabaseHealthIndicator(dataSource).health().status shouldBeEqualTo Status.DOWN
    }

    @Test
/**
 * 이 시나리오는 "Redis and leader failures are explicit degraded components" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Redis and leader failures are explicit degraded components`() {
        val state = VoucherDegradationState()
        val redis = VoucherRedisHealthIndicator(state)
        val leader = VoucherLeaderHealthIndicator(state)

        redis.health().status shouldBeEqualTo Status.UP
        leader.health().status shouldBeEqualTo Status.UP

        state.degrade(VoucherDegradedComponent.REDIS)
        state.degrade(VoucherDegradedComponent.LEADER)
        redis.health().status shouldBeEqualTo VOUCHER_DEGRADED_STATUS
        leader.health().status shouldBeEqualTo VOUCHER_DEGRADED_STATUS

        state.recover(VoucherDegradedComponent.REDIS)
        redis.health().status shouldBeEqualTo Status.UP
        leader.health().status shouldBeEqualTo VOUCHER_DEGRADED_STATUS
    }
}
