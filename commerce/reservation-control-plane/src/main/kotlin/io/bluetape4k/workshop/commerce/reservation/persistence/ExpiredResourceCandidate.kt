package io.bluetape4k.workshop.commerce.reservation.persistence

import java.io.Serializable
import java.time.Instant

/** sweep limit 적용 전에 hold와 offer expiry stream을 병합할 때 사용하는 minimal projection입니다. */
internal data class ExpiredResourceCandidate(
    val resourceId: Long,
    val expiresAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
