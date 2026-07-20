package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

internal class VoucherRetentionIntegrationTest {
    @Test
    fun `audit terminal replay and applied event cutoffs preserve their minimum windows`() {
        val now = Instant.parse("2026-07-20T00:00:00Z")
        val policy = VoucherRetentionPolicy(VoucherRetentionProperties())

        policy.cutoffs(now, Duration.ofDays(2)) shouldBeEqualTo
            VoucherRetentionCutoffs(
                auditBefore = now.minus(Duration.ofDays(90)),
                terminalBefore = now.minus(Duration.ofDays(9)),
                appliedEventBefore = now.minus(Duration.ofDays(30)),
            )
    }
}
