package io.bluetape4k.workshop.commerce.ticket.salecontrol.internal

import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketLockRank
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketSaleEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketSalePolicies
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketSales
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePolicySnapshot
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePurchaseAuthority
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePurchasePolicy
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.Serial
import java.time.Instant
import java.util.UUID

sealed class SaleAdmissionFailure(code: String) : IllegalStateException(code) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

class SaleNotStarted : SaleAdmissionFailure("sale_not_started")
class SaleClosed : SaleAdmissionFailure("sale_closed")
class SaleNotOpen : SaleAdmissionFailure("sale_not_open")
class StaleSalePolicy : SaleAdmissionFailure("stale_sale_policy")

/** Bluetape4k Exposed JDBC repository로 구현한 sale authority입니다. */
class TicketSaleRepository : TicketExposedJdbcRepository<TicketSaleEntity, UUID>(TicketSaleEntity::class.java) {
    fun requireOpen(
        transaction: TicketJdbcTransaction,
        expected: SalePolicySnapshot,
        now: Instant,
    ): SalePurchasePolicy = with(transaction) {
        acquire(TicketLockRank.SALE)
        val sale = TicketSales.selectAll()
            .where { TicketSales.id eq expected.saleId }
            .forUpdate()
            .singleOrNull()
            ?: throw SaleClosed()
        val policyVersion = sale[TicketSales.currentPolicyVersion]
        TicketSalePolicies.selectAll()
            .where {
                (TicketSalePolicies.saleId eq expected.saleId) and
                    (TicketSalePolicies.policyVersion eq policyVersion)
            }
            .forUpdate()
            .singleOrNull()
            ?.let { policy ->
                val opensAt = sale[TicketSales.opensAt]
                val closesAt = sale[TicketSales.closesAt]
                if (expected.policyVersion != policyVersion) throw StaleSalePolicy()
                if (now.isBefore(expected.opensAt) || now.isBefore(opensAt)) throw SaleNotStarted()
                if (!now.isBefore(closesAt)) throw SaleClosed()
                if (sale[TicketSales.state] != SaleState.OPEN.code) throw SaleNotOpen()
                SalePurchasePolicy(
                    saleId = expected.saleId,
                    policyVersion = policyVersion,
                    perUserLimit = policy[TicketSalePolicies.perUserLimit],
                    maxQuantity = policy[TicketSalePolicies.maxQuantity],
                    holdSeconds = policy[TicketSalePolicies.holdSeconds],
                )
            }
            ?: throw SaleClosed()
    }
}

/** purchase transaction 안에서 sale window와 immutable policy를 다시 검증합니다. */
class SaleService(
    private val sales: TicketSaleRepository = TicketSaleRepository(),
) : SalePurchaseAuthority {
    override fun requireOpen(
        transaction: TicketJdbcTransaction,
        expected: SalePolicySnapshot,
        now: Instant,
    ): SalePurchasePolicy = sales.requireOpen(transaction, expected, now)
}
