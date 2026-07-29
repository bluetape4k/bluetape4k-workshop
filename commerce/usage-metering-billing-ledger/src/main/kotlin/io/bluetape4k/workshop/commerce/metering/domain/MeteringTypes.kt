package io.bluetape4k.workshop.commerce.metering.domain

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank

private const val TENANT_ID_MAX_LENGTH = 64
private const val METER_CODE_MAX_LENGTH = 64
private const val SOURCE_SYSTEM_MAX_LENGTH = 64
private const val SOURCE_EVENT_ID_MAX_LENGTH = 128
private const val IDEMPOTENCY_KEY_MAX_LENGTH = 256

@JvmInline
value class TenantId(val value: String) {
    init {
        requireBoundedIdentifier(value, "tenantId", TENANT_ID_MAX_LENGTH)
    }
}

@JvmInline
value class MeterCode(val value: String) {
    init {
        requireBoundedIdentifier(value, "meterCode", METER_CODE_MAX_LENGTH)
    }
}

@JvmInline
value class SourceSystem(val value: String) {
    init {
        requireBoundedIdentifier(value, "sourceSystem", SOURCE_SYSTEM_MAX_LENGTH)
    }
}

@JvmInline
value class SourceEventId(val value: String) {
    init {
        requireBoundedIdentifier(value, "sourceEventId", SOURCE_EVENT_ID_MAX_LENGTH)
    }
}

/**
 * raw idempotency key는 HTTP boundary에만 존재하며 persistence나 logging 전에 digest해야 합니다.
 */
@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        requireBoundedIdentifier(value, "idempotencyKey", IDEMPOTENCY_KEY_MAX_LENGTH)
    }

    override fun toString(): String = "IdempotencyKey([REDACTED])"
}

private fun requireBoundedIdentifier(value: String, name: String, maxLength: Int) {
    value.requireNotBlank(name)
    value.length.requireInRange(1, maxLength, "$name.length")
}
