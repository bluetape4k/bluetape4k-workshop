package io.bluetape4k.workshop.commerce.ticket.salecontrol.api

import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
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
