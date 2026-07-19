package io.bluetape4k.workshop.commerce.reservation.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.io.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

internal enum class LocalTimeRejection { DST_GAP, DST_OVERLAP, OFFSET_MISMATCH }

internal sealed interface LocalTimeResolution : Serializable {
    data class Resolved(
        val instant: Instant,
        val offset: ZoneOffset,
    ) : LocalTimeResolution {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    data class Rejected(
        val reason: LocalTimeRejection,
    ) : LocalTimeResolution {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}

/** Keeps local-time ambiguity out of reservation commands by requiring an explicit offset during DST overlap. */
internal object ReservationTimePolicy : KLogging() {
    fun project(
        instant: Instant,
        zoneId: ZoneId,
    ): OffsetDateTime = instant.atZone(zoneId).toOffsetDateTime()

    fun resolve(
        localDateTime: LocalDateTime,
        zoneId: ZoneId,
        preferredOffset: ZoneOffset? = null,
    ): LocalTimeResolution {
        val validOffsets = zoneId.rules.getValidOffsets(localDateTime)
        if (validOffsets.isEmpty()) {
            log.debug { "reservation_local_time_rejected reason=DST_GAP" }
            return LocalTimeResolution.Rejected(LocalTimeRejection.DST_GAP)
        }
        if (validOffsets.size > 1 && preferredOffset == null) {
            log.debug { "reservation_local_time_rejected reason=DST_OVERLAP" }
            return LocalTimeResolution.Rejected(LocalTimeRejection.DST_OVERLAP)
        }
        val offset = preferredOffset ?: validOffsets.single()
        if (offset !in validOffsets) {
            log.debug { "reservation_local_time_rejected reason=OFFSET_MISMATCH" }
            return LocalTimeResolution.Rejected(LocalTimeRejection.OFFSET_MISMATCH)
        }
        return LocalTimeResolution.Resolved(localDateTime.toInstant(offset), offset)
    }
}
