package io.bluetape4k.workshop.commerce.ticket.salecontrol.api

import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Immutable sale policy exposed to admission and purchase. */
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

/** Read boundary for authoritative sale policy. */
fun interface SaleQueries {
    fun get(saleId: UUID): SalePolicySnapshot?
}

/** Purchase limits read under the authoritative sale row's shared lock. */
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

/** Transaction-aware sale revalidation boundary used by purchase. */
fun interface SalePurchaseAuthority {
    fun requireOpen(
        transaction: TicketJdbcTransaction,
        expected: SalePolicySnapshot,
        now: Instant,
    ): SalePurchasePolicy
}
