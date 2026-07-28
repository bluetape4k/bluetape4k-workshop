package io.bluetape4k.workshop.commerce.ticket.salecontrol.api

import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** admission과 purchase에 노출되는 immutable sale policy입니다. */
data class SalePolicySnapshot(
    val saleId: UUID,
    val state: SaleState,
    val policyVersion: Long,
    val opensAt: Instant,
    val closesAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** authoritative sale policy의 read boundary입니다. */
fun interface SaleQueries {
    fun get(saleId: UUID): SalePolicySnapshot?
}

/** authoritative sale row의 shared lock 아래에서 읽는 purchase limit입니다. */
data class SalePurchasePolicy(
    val saleId: UUID,
    val policyVersion: Long,
    val perUserLimit: Int,
    val maxQuantity: Int,
    val holdSeconds: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** purchase에서 사용하는 transaction-aware sale revalidation boundary입니다. */
fun interface SalePurchaseAuthority {
    fun requireOpen(
        transaction: TicketJdbcTransaction,
        expected: SalePolicySnapshot,
        now: Instant,
    ): SalePurchasePolicy
}
