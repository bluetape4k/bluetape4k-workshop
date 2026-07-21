package io.bluetape4k.workshop.commerce.ticket.salecontrol.internal

import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketLockRank
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePolicySnapshot
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePurchaseAuthority
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePurchasePolicy
import java.io.Serial
import java.time.Instant
import java.time.OffsetDateTime

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

/** Revalidates the sale window and immutable policy inside the purchase transaction. */
class SaleService : SalePurchaseAuthority {
    override fun requireOpen(
        transaction: TicketJdbcTransaction,
        expected: SalePolicySnapshot,
        now: Instant,
    ): SalePurchasePolicy =
        with(transaction) {
            acquire(TicketLockRank.SALE)
            connection.prepareStatement(
                """
                SELECT s.state, s.current_policy_version, s.opens_at, s.closes_at,
                       p.per_user_limit, p.max_quantity, p.hold_seconds
                FROM ticket_sales s
                JOIN ticket_sale_policy_versions p
                  ON p.sale_id = s.sale_id AND p.policy_version = s.current_policy_version
                WHERE s.sale_id = ?
                FOR SHARE OF s, p
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, expected.saleId)
                statement.executeQuery().use { result ->
                    if (!result.next()) throw SaleClosed()
                    val policyVersion = result.getLong("current_policy_version")
                    val opensAt = result.getObject("opens_at", OffsetDateTime::class.java).toInstant()
                    val closesAt = result.getObject("closes_at", OffsetDateTime::class.java).toInstant()
                    if (expected.policyVersion != policyVersion) throw StaleSalePolicy()
                    if (now.isBefore(expected.opensAt)) throw SaleNotStarted()
                    if (now.isBefore(opensAt)) throw SaleNotStarted()
                    if (!now.isBefore(closesAt)) throw SaleClosed()
                    if (result.getString("state") != SaleState.OPEN.code) throw SaleNotOpen()
                    SalePurchasePolicy(
                        saleId = expected.saleId,
                        policyVersion = policyVersion,
                        perUserLimit = result.getInt("per_user_limit"),
                        maxQuantity = result.getInt("max_quantity"),
                        holdSeconds = result.getLong("hold_seconds"),
                    )
                }
            }
        }
}
