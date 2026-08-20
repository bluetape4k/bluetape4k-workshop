package io.bluetape4k.workshop.optimization.fieldservice.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

private fun validateFieldServiceId(value: String, field: String): String {
    val normalized = try {
        value.requireNotBlank(field).trim()
    } catch (failure: IllegalArgumentException) {
        throw InvalidFieldServiceInput("$field must not be blank", failure)
    }
    if (normalized.length > FieldServiceLimits.MAX_STRING_LENGTH) {
        throw InvalidFieldServiceInput("$field exceeds ${FieldServiceLimits.MAX_STRING_LENGTH} characters")
    }
    return normalized
}

/** Synthetic worker 식별자입니다. */
@JvmInline
value class WorkerId(val value: String) : Serializable {
    init {
        validateFieldServiceId(value, "workerId")
    }
}

/** Synthetic visit 식별자입니다. */
@JvmInline
value class VisitId(val value: String) : Serializable {
    init {
        validateFieldServiceId(value, "visitId")
    }
}

/** Field Service plan stream 식별자입니다. */
@JvmInline
value class PlanId(val value: String) : Serializable {
    init {
        validateFieldServiceId(value, "planId")
    }
}

/** 기준 데이터 집계 식별자입니다. */
@JvmInline
value class AggregateId(val value: String) : Serializable {
    init {
        validateFieldServiceId(value, "aggregateId")
    }
}

/** Synthetic coordinate 식별자입니다. */
@JvmInline
value class CoordinateId(val value: String) : Serializable {
    init {
        validateFieldServiceId(value, "coordinateId")
    }
}

/** Worker가 가진 닫힌 skill 토큰입니다. */
@JvmInline
value class Skill(val value: String) : Serializable {
    init {
        validateFieldServiceId(value, "skill")
    }
}

/** 기준 데이터 식별자입니다. */
@JvmInline
value class DatasetId(val value: String) : Serializable {
    init {
        validateFieldServiceId(value, "datasetId")
    }
}

/** Provider 요청 식별자입니다. */
@JvmInline
value class ProviderRequestId(val value: String) : Serializable {
    init {
        validateFieldServiceId(value, "providerRequestId")
    }
}

/** Event idempotency key입니다. */
@JvmInline
value class EventKey(val value: String) : Serializable {
    init {
        val normalized = try {
            value.requireNotBlank("eventKey")
        } catch (failure: IllegalArgumentException) {
            throw InvalidFieldServiceInput("eventKey must not be blank", failure)
        }
        if (normalized.toByteArray(Charsets.UTF_8).size > FieldServiceLimits.MAX_KEY_LENGTH) {
            throw InvalidFieldServiceInput("eventKey exceeds ${FieldServiceLimits.MAX_KEY_LENGTH} bytes")
        }
    }
}

/** HTTP mutation 재시도 식별자입니다. */
@JvmInline
value class IdempotencyKey(val value: String) : Serializable {
    init {
        val normalized = try {
            value.requireNotBlank("idempotencyKey")
        } catch (failure: IllegalArgumentException) {
            throw InvalidFieldServiceInput("idempotencyKey must not be blank", failure)
        }
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        if (bytes.size > FieldServiceLimits.MAX_KEY_LENGTH ||
            normalized.any { it.code !in 0x21..0x7e }
        ) {
            throw InvalidFieldServiceInput("idempotencyKey must be printable ASCII and <= ${FieldServiceLimits.MAX_KEY_LENGTH} bytes")
        }
    }
}

/** SHA-256 canonical payload digest의 lowercase hexadecimal 표현입니다. */
@JvmInline
value class EventDigest(val value: String) : Serializable {
    init {
        if (!value.matches(HEX_256)) {
            throw InvalidFieldServiceInput("event digest must be a 64-character lowercase hexadecimal SHA-256")
        }
    }

    companion object {
        private val HEX_256 = Regex("[0-9a-f]{64}")
    }
}
