package io.bluetape4k.workshop.optimization.shiftcoverage.domain

import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

private fun idValue(raw: String, field: String): String {
    val value = try {
        raw.requireNotBlank(field).trim()
    } catch (failure: IllegalArgumentException) {
        throw InvalidShiftCoverageInput("$field must not be blank", failure)
    }
    if (value.length > ShiftCoverageLimits.MAX_STRING_LENGTH ||
        value.toByteArray(Charsets.UTF_8).size > ShiftCoverageLimits.MAX_KEY_BYTES
    ) {
        throw InvalidShiftCoverageInput("$field exceeds ${ShiftCoverageLimits.MAX_KEY_BYTES} UTF-8 bytes")
    }
    return value
}

private fun uuidValue(field: String): String = Uuid.V7.nextId().toString().also {
    if (it.isBlank()) throw InvalidShiftCoverageInput("$field factory returned a blank UUID")
}

/** synthetic worker 식별자입니다. */
@JvmInline
value class WorkerId(val value: String) : Serializable { init { idValue(value, "workerId") } }

/** site scope 식별자입니다. */
@JvmInline
value class SiteId(val value: String) : Serializable { init { idValue(value, "siteId") } }

/** canonical aggregate 식별자입니다. */
@JvmInline
value class AggregateId(val value: String) : Serializable { init { idValue(value, "aggregateId") } }

/** canonical dataset 식별자입니다. */
@JvmInline
value class DatasetId(val value: String) : Serializable { init { idValue(value, "datasetId") } }

/** shift 식별자입니다. */
@JvmInline
value class ShiftId(val value: String) : Serializable { init { idValue(value, "shiftId") } }

/** assignment 식별자입니다. */
@JvmInline
value class AssignmentId(val value: String) : Serializable {
    init { idValue(value, "assignmentId") }
    companion object { fun new(): AssignmentId = AssignmentId(uuidValue("assignmentId")) }
}

/** plan 식별자입니다. */
@JvmInline
value class PlanId(val value: String) : Serializable {
    init { idValue(value, "planId") }
    companion object { fun new(): PlanId = PlanId(uuidValue("planId")) }
}

/** 사람이 확인하는 swap 요청 식별자입니다. */
@JvmInline
value class SwapRequestId(val value: String) : Serializable {
    init { idValue(value, "swapRequestId") }
    companion object { fun new(): SwapRequestId = SwapRequestId(uuidValue("swapRequestId")) }
}

/** provider callback/event 식별자입니다. */
@JvmInline
value class EventId(val value: String) : Serializable { init { idValue(value, "eventId") } }

/** durable replan generation 식별자입니다. */
@JvmInline
value class GenerationId(val value: String) : Serializable {
    init { idValue(value, "generationId") }
    companion object { fun new(): GenerationId = GenerationId(uuidValue("generationId")) }
}

/** UTF-8 canonical snapshot의 lowercase SHA-256 digest입니다. */
@JvmInline
value class SnapshotDigest(val value: String) : Serializable {
    init {
        if (!HEX_256.matches(value)) {
            throw InvalidShiftCoverageInput("snapshot digest must be lowercase SHA-256 hexadecimal")
        }
    }

    companion object { private val HEX_256 = Regex("[0-9a-f]{64}") }
}

/** HTTP mutation idempotency key입니다. */
@JvmInline
value class IdempotencyKey(val value: String) : Serializable {
    init {
        val normalized = try { value.requireNotBlank("idempotencyKey") } catch (failure: IllegalArgumentException) {
            throw InvalidShiftCoverageInput("idempotencyKey must not be blank", failure)
        }
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        if (bytes.size > ShiftCoverageLimits.MAX_KEY_BYTES || normalized.any { it.code !in 0x21..0x7e }) {
            throw InvalidShiftCoverageInput("idempotencyKey must be printable ASCII and <= ${ShiftCoverageLimits.MAX_KEY_BYTES} bytes")
        }
    }
}

/** provider effect에 노출하는 opaque token입니다. */
@JvmInline
value class EffectKey(val value: String) : Serializable {
    init {
        if (value.length != ShiftCoverageLimits.OPAQUE_TOKEN_LENGTH || value.any { it.code !in 0x21..0x7e }) {
            throw InvalidShiftCoverageInput("effectKey must be a ${ShiftCoverageLimits.OPAQUE_TOKEN_LENGTH}-character opaque token")
        }
    }

    companion object {
        fun new(): EffectKey = EffectKey(Base58.randomString(ShiftCoverageLimits.OPAQUE_TOKEN_LENGTH))
    }
}

/** callback request를 묶는 provider namespace입니다. */
@JvmInline
value class ProviderName(val value: String) : Serializable { init { idValue(value, "provider") } }
