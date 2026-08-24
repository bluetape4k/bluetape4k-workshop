package io.bluetape4k.workshop.optimization.shiftcoverage.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

enum class ShiftCoverageTimeBoundaryCode {
    TIMEZONE_GAP,
    TIMEZONE_AMBIGUOUS,
    TIMEZONE_OFFSET_INVALID,
}

class ShiftCoverageTimeBoundaryException(
    val code: ShiftCoverageTimeBoundaryCode,
    message: String = code.name,
) : IllegalArgumentException(message)

/** local wall-clock input을 명시적인 instant로 바꾸는 DST-safe boundary입니다. */
class ShiftCoverageTimeZoneResolver {
    fun resolve(local: LocalDateTime, zoneId: ZoneId, explicitOffset: ZoneOffset? = null): Instant {
        val validOffsets = zoneId.rules.getValidOffsets(local)
        if (validOffsets.isEmpty()) {
            throw ShiftCoverageTimeBoundaryException(
                ShiftCoverageTimeBoundaryCode.TIMEZONE_GAP,
                "local time does not exist in ${zoneId.id}: $local",
            )
        }
        if (explicitOffset != null) {
            if (explicitOffset !in validOffsets) {
                throw ShiftCoverageTimeBoundaryException(
                    ShiftCoverageTimeBoundaryCode.TIMEZONE_OFFSET_INVALID,
                    "offset does not match ${zoneId.id}: $explicitOffset",
                )
            }
            return local.toInstant(explicitOffset)
        }
        if (validOffsets.size > 1) {
            throw ShiftCoverageTimeBoundaryException(
                ShiftCoverageTimeBoundaryCode.TIMEZONE_AMBIGUOUS,
                "local time has multiple offsets in ${zoneId.id}: $local",
            )
        }
        return local.toInstant(validOffsets.single())
    }
}
