package io.bluetape4k.workshop.commerce.voucher.fixture

import io.bluetape4k.workshop.commerce.voucher.persistence.AuditTable
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignTable
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimTable
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxTable
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewTable
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

internal data class VoucherFixtureResetResult(
    val clearedSignals: Int,
    val deletedRows: Int,
)

/** 선택된 workshop tenant만 삭제하며 production profile로 넘어가지 않습니다. */
@Service
@Profile("local", "demo", "test")
internal class VoucherFixtureResetService(
    private val transactions: VoucherTransactionRunner,
    private val scenarios: VoucherScenarioFixtures,
) {
    fun reset(tenantId: String): VoucherFixtureResetResult =
        transactions.foregroundTransaction {
            val deletedRows =
                ReviewTable.deleteWhere { ReviewTable.tenantId eq tenantId } +
                    AuditTable.deleteWhere { AuditTable.tenantId eq tenantId } +
                    EventInboxTable.deleteWhere { EventInboxTable.tenantId eq tenantId } +
                    ClaimTable.deleteWhere { ClaimTable.tenantId eq tenantId } +
                    CampaignTable.deleteWhere { CampaignTable.tenantId eq tenantId }
            VoucherFixtureResetResult(scenarios.reset(tenantId), deletedRows)
        }
}
