package io.bluetape4k.workshop.optimization.lastmile.domain

private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

private fun validateIdentifier(value: String, name: String): String {
    require(IDENTIFIER_PATTERN.matches(value)) { "$name must be 1-64 ASCII characters" }
    return value
}

@JvmInline
value class JobId(val value: String) {
    init {
        validateIdentifier(value, "jobId")
    }
}

@JvmInline
value class VehicleId(val value: String) {
    init {
        validateIdentifier(value, "vehicleId")
    }
}

@JvmInline
value class DriverId(val value: String) {
    init {
        validateIdentifier(value, "driverId")
    }
}

@JvmInline
value class CoordinateId(val value: String) {
    init {
        validateIdentifier(value, "coordinateId")
    }
}

@JvmInline
value class LastMilePlanId(val value: String) {
    init {
        validateIdentifier(value, "planId")
    }
}

@JvmInline
value class EventId(val value: String) {
    init {
        validateIdentifier(value, "eventId")
    }
}

@JvmInline
value class ProviderRevision(val value: Long) {
    init {
        require(value >= 0L) { "provider revision must be non-negative" }
    }
}

@JvmInline
value class CarrierVersion(val value: Long) {
    init {
        require(value >= 0L) { "carrier version must be non-negative" }
    }
}

object LastMileLimits {
    const val MAX_JOBS = 128
    const val MAX_VEHICLES = 32
    const val MAX_MATRIX_COORDINATES = 512
    const val MAX_MATRIX_EDGES = 4_096
    const val MAX_STOPS_PER_ROUTE = 256
    const val MAX_EVENT_PAYLOAD = 8_192
}
