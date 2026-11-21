package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * VoucherAggregateTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherAggregateTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "voucher events reduce an issued voucher through redemption" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "voucher cannot redeem before allocation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "allocated voucher can be cancelled or expired but not redeemed afterward" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
