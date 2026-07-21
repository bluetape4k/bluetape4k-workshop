package io.bluetape4k.workshop.commerce.ticket.ticketing.api

import io.bluetape4k.workshop.commerce.ticket.domain.TicketState
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Operations-safe ticket effect projection. */
data class TicketEffectSnapshot(
    val operationId: UUID,
    val state: TicketState,
    val revision: Long,
    val updatedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Read boundary for ticket effect state. */
fun interface TicketingQueries {
    fun effect(operationId: UUID): TicketEffectSnapshot?
}
