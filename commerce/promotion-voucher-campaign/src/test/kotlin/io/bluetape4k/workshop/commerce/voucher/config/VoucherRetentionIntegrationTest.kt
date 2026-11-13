package io.bluetape4k.workshop.commerce.voucher.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.days
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

internal class VoucherRetentionIntegrationTest {
    @Test
    fun `audit terminal replay and applied event cutoffs preserve their minimum windows`() {
        val now = Instant.parse("2026-07-20T00:00:00Z")
        val policy = VoucherRetentionPolicy(VoucherRetentionProperties())

        policy.cutoffs(now, 2.days()) shouldBeEqualTo
            VoucherRetentionCutoffs(
                auditBefore = now.minus(90.days()),
                terminalBefore = now.minus(9.days()),
                appliedEventBefore = now.minus(30.days()),
            )
    }
}
