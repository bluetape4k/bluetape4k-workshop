package io.bluetape4k.workshop.commerce.reservation.persistence

import java.io.Serializable
import java.time.Instant

internal data class ExpiredResourceCandidate(
    val resourceId: Long,
    val expiresAt: Instant,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}
