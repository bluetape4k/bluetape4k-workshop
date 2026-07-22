package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import java.time.Instant
import java.util.UUID

internal data class VoucherAggregate(
    val tenantId: TenantId?,
    val campaignId: UUID?,
    val voucherId: UUID?,
    val subjectId: UUID?,
    val state: VoucherState,
    val policyVersion: Long,
    val expiresAt: Instant?,
    val version: Long,
) {
    private fun apply(event: VoucherEvent): VoucherAggregate =
        when (event) {
            is VoucherEvent.VoucherIssued -> {
                if (voucherId != null) fail("voucher is already issued")
                copy(
                    tenantId = event.tenantId,
                    campaignId = event.campaignId,
                    voucherId = event.voucherId,
                    subjectId = event.subjectId,
                    version = version + 1,
                )
            }

            is VoucherEvent.VoucherAllocated -> {
                requireIssued()
                if (state != VoucherState.ELIGIBLE) fail("voucher cannot allocate from $state")
                copy(
                    state = VoucherState.ALLOCATED,
                    policyVersion = event.policyVersion,
                    expiresAt = event.expiresAt,
                    version = version + 1,
                )
            }

            is VoucherEvent.VoucherRedeemed -> {
                requireIssued()
                if (state != VoucherState.ALLOCATED) fail("voucher cannot redeem from $state")
                if (expiresAt != null && event.redeemedAt.isAfter(expiresAt)) {
                    fail("voucher allocation is expired")
                }
                copy(state = VoucherState.REDEEMED, version = version + 1)
            }

            VoucherEvent.VoucherReleased -> transitionFromAllocation(VoucherState.RELEASED)
            VoucherEvent.VoucherExpired -> transitionFromAllocation(VoucherState.EXPIRED)
            VoucherEvent.VoucherRevoked -> {
                requireIssued()
                if (state == VoucherState.REDEEMED || state == VoucherState.REVOKED) {
                    fail("voucher cannot revoke from $state")
                }
                copy(state = VoucherState.REVOKED, version = version + 1)
            }
        }

    private fun transitionFromAllocation(target: VoucherState): VoucherAggregate {
        requireIssued()
        if (state != VoucherState.ALLOCATED) fail("voucher cannot transition from $state")
        return copy(state = target, version = version + 1)
    }

    private fun requireIssued() {
        if (voucherId == null) fail("voucher must be issued first")
    }

    private fun fail(message: String): Nothing = throw DomainTransitionException(message)

    companion object {
        fun replay(events: List<VoucherEvent>): VoucherAggregate =
            events.fold(empty()) { aggregate, event -> aggregate.apply(event) }

        private fun empty(): VoucherAggregate =
            VoucherAggregate(
                tenantId = null,
                campaignId = null,
                voucherId = null,
                subjectId = null,
                state = VoucherState.ELIGIBLE,
                policyVersion = 0,
                expiresAt = null,
                version = 0,
            )
    }
}
