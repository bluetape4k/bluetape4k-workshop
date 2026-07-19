package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.sql.Connection
import javax.sql.DataSource

internal class VoucherHealthIndicatorsTest {
    @Test
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
