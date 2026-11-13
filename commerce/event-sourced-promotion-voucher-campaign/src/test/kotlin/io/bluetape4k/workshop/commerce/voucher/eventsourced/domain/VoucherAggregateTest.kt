package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

internal class VoucherAggregateTest {

    companion object: KLogging()

    @Test
    fun `voucher events reduce an issued voucher through redemption`() {
        val voucherId = UUID.randomUUID()
        val campaignId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val allocatedAt = Instant.parse("2026-07-22T00:10:00Z")

        val voucher = VoucherAggregate.replay(
            listOf(
                VoucherEvent.VoucherIssued(TenantId("tenant-a"), campaignId, voucherId, userId),
                VoucherEvent.VoucherAllocated(policyVersion = 1, expiresAt = allocatedAt.plusSeconds(600)),
                VoucherEvent.VoucherRedeemed(redeemedAt = allocatedAt.plusSeconds(60)),
            ),
        )

        log.debug { "voucher=$voucher" }
        voucher.state shouldBeEqualTo VoucherState.REDEEMED
        voucher.policyVersion shouldBeEqualTo 1
        voucher.version shouldBeEqualTo 3
    }

    @Test
    fun `voucher cannot redeem before allocation`() {
        assertFailsWith<DomainTransitionException> {
            VoucherAggregate.replay(
                listOf(
                    VoucherEvent.VoucherIssued(
                        TenantId("tenant-a"),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                    ),
                    VoucherEvent.VoucherRedeemed(Instant.parse("2026-07-22T00:10:00Z")),
                ),
            )
        }
    }

    @Test
    fun `allocated voucher can be cancelled or expired but not redeemed afterward`() {
        val issued = VoucherEvent.VoucherIssued(
            tenantId = TenantId("tenant-a"),
            campaignId = UUID.randomUUID(),
            voucherId = UUID.randomUUID(),
            subjectId = UUID.randomUUID(),
        )
        log.debug { "issued=$issued" }

        val allocation = VoucherEvent.VoucherAllocated(1, Instant.parse("2026-07-22T00:20:00Z"))
        log.debug { "allocation=$allocation" }

        VoucherAggregate.replay(listOf(issued, allocation, VoucherEvent.VoucherReleased))
            .state shouldBeEqualTo VoucherState.RELEASED

        VoucherAggregate.replay(listOf(issued, allocation, VoucherEvent.VoucherExpired))
            .state shouldBeEqualTo VoucherState.EXPIRED

        assertFailsWith<DomainTransitionException> {
            VoucherAggregate.replay(
                listOf(
                    issued,
                    allocation,
                    VoucherEvent.VoucherExpired,
                    VoucherEvent.VoucherRedeemed(Instant.parse("2026-07-22T00:10:00Z")),
                ),
            )
        }
    }
}
