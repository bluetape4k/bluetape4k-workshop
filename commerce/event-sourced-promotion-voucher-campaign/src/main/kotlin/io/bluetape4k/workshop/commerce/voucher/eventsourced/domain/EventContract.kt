package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal const val MAX_EVENT_PAYLOAD_BYTES = 64 * 1024
internal const val MAX_EVENT_PAYLOAD_DEPTH = 16
internal const val MAX_EVENT_PAYLOAD_STRING_BYTES = 8 * 1024
internal const val MAX_SNAPSHOT_BYTES = 1024 * 1024
internal const val MAX_UPCAST_STEPS = 4

private const val UUID_V7 = 7

internal data class StreamReference(
    val type: String,
    val id: UUID,
    val version: Long,
) {
    init {
        type.requireNotBlank("stream.type")
        version.requireZeroOrPositiveNumber("stream.version")
    }
}

internal data class EventPayload(val canonicalJson: String) {
    init {
        canonicalJson.requireNotBlank("payload")
        payloadByteSize().requireLe(MAX_EVENT_PAYLOAD_BYTES, "payload.bytes")
        SENSITIVE_FIELD_NAMES.none(::containsField).requireEquals(true, "payload.excludesSensitiveFields")
        validateJsonBounds(canonicalJson)
    }

    private fun payloadByteSize(): Int = canonicalJson.toByteArray(StandardCharsets.UTF_8).size

    private fun containsField(fieldName: String): Boolean =
        canonicalJson.contains("\"$fieldName\"", ignoreCase = true)

    private companion object {
        val SENSITIVE_FIELD_NAMES =
            setOf(
                "voucherCode",
                "email",
                "phone",
                "accessToken",
                "token",
                "idempotencyKey",
                "authorization",
                "authorizationHeader",
                "user",
                "userId",
                "device",
                "deviceId",
                "ip",
                "ipAddress",
            )

        fun validateJsonBounds(json: String) {
            var depth = 0
            var stringBytes = 0
            var inString = false
            var escaped = false
            json.forEach { char ->
                if (inString) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> {
                            inString = false
                            stringBytes = 0
                        }
                        else -> {
                            stringBytes += char.toString().toByteArray(StandardCharsets.UTF_8).size
                            stringBytes.requireLe(MAX_EVENT_PAYLOAD_STRING_BYTES, "payload.stringBytes")
                        }
                    }
                } else {
                    depth = updateDepth(char, depth)
                    if (char == '"') inString = true
                }
            }
            (!inString && depth == 0).requireEquals(true, "payload.balancedJson")
        }

        private fun updateDepth(char: Char, depth: Int): Int {
            val nextDepth =
                when (char) {
                    '{', '[' -> depth + 1
                    '}', ']' -> depth - 1
                    else -> depth
                }
            return nextDepth
                .requireZeroOrPositiveNumber("payload.depth")
                .requireLe(MAX_EVENT_PAYLOAD_DEPTH, "payload.depth")
        }
    }
}

internal data class EventEnvelope(
    val eventId: UUID,
    val tenantId: TenantId,
    val stream: StreamReference,
    val globalPosition: Long,
    val eventType: String,
    val schemaVersion: Int,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val correlationId: String,
    val causationId: String?,
    val actorSurrogate: String,
    val payload: EventPayload,
    val actorHmacKeyVersion: Int = 1,
) {
    val canonicalChecksum: String
        get() = checksumOf(canonicalFields())

    init {
        eventId.version().requireEquals(UUID_V7, "eventId.version")
        globalPosition.requirePositiveNumber("globalPosition")
        eventType.requireNotBlank("eventType")
        schemaVersion.requireInRange(1, Int.MAX_VALUE, "schemaVersion")
        correlationId.requireNotBlank("correlationId")
        actorSurrogate.requireNotBlank("actorSurrogate")
        actorHmacKeyVersion.requirePositiveNumber("actorHmacKeyVersion")
        recordedAt.requireGe(occurredAt, "recordedAt")
    }

    private fun canonicalFields(): List<Any> =
        listOf(
            eventId,
            tenantId.value,
            stream.type,
            stream.id,
            stream.version,
            globalPosition,
            eventType,
            schemaVersion,
            occurredAt,
            recordedAt,
            correlationId,
            causationId.orEmpty(),
            actorSurrogate,
            actorHmacKeyVersion,
            payload.canonicalJson,
        )

    private companion object {
        fun checksumOf(fields: List<Any>): String =
            fields
                .joinToString("|")
                .toByteArray(StandardCharsets.UTF_8)
                .let { MessageDigest.getInstance("SHA-256").digest(it) }
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal data class SerializedEvent(
    val eventType: String,
    val schemaVersion: Int,
    val payload: EventPayload,
)

internal data class EventUpcaster(
    val eventType: String,
    val fromVersion: Int,
    val toVersion: Int,
    val transform: (EventPayload) -> EventPayload,
) {
    init {
        fromVersion.requirePositiveNumber("fromVersion")
        toVersion.requireEquals(fromVersion + 1, "toVersion")
    }
}

internal data class UpcastGoldenFixture(
    val eventType: String,
    val fromVersion: Int,
    val toVersion: Int,
    val input: EventPayload,
    val expected: EventPayload,
) {
    init {
        fromVersion.requirePositiveNumber("fromVersion")
        toVersion.requireEquals(fromVersion + 1, "toVersion")
    }
}

internal data class EventSchema<T : Any>(
    val eventType: String,
    val currentVersion: Int,
    val decode: (EventPayload) -> T,
) {
    init {
        eventType.requireNotBlank("eventType")
        currentVersion.requirePositiveNumber("currentVersion")
    }
}

internal class UnknownEventSchemaException(message: String) : IllegalArgumentException(message)

internal class EventSchemaRegistry(
    schemas: Set<EventSchema<*>>,
    upcasters: Set<EventUpcaster>,
) {
    private val schemasByType = schemas.associateBy { it.eventType }
    private val upcastersByStep = upcasters.associateBy(::stepKey)

    init {
        schemasByType.size.requireEquals(schemas.size, "schemasByType.size")
        upcastersByStep.size.requireEquals(upcasters.size, "upcastersByStep.size")
    }

    fun decode(event: SerializedEvent): Any = decodeWithSchema(requireSchema(event), event)

    private fun requireSchema(event: SerializedEvent): EventSchema<*> =
        schemasByType[event.eventType]
            ?: throw UnknownEventSchemaException("unknown event type ${event.eventType}")

    private fun decodeWithSchema(schema: EventSchema<*>, event: SerializedEvent): Any {
        val payload = upcast(event, schema.currentVersion)
        return schema.decode(payload)
    }

    private fun upcast(event: SerializedEvent, targetVersion: Int): EventPayload {
        var version = event.schemaVersion
        var payload = event.payload
        repeat(MAX_UPCAST_STEPS) {
            if (version == targetVersion) return payload
            payload = requireUpcaster(event.eventType, version).transform(payload)
            version += 1
        }
        if (version == targetVersion) return payload
        throw UnknownEventSchemaException("upcast chain exceeds $MAX_UPCAST_STEPS steps")
    }

    private fun requireUpcaster(eventType: String, fromVersion: Int): EventUpcaster =
        upcastersByStep[Triple(eventType, fromVersion, fromVersion + 1)]
            ?: throw UnknownEventSchemaException("no upcaster for $eventType v$fromVersion")

    private fun stepKey(upcaster: EventUpcaster): Triple<String, Int, Int> =
        Triple(upcaster.eventType, upcaster.fromVersion, upcaster.toVersion)
}

internal object EventReplay {
    fun ordered(events: List<EventEnvelope>): List<EventEnvelope> {
        events.map(EventEnvelope::eventId).toSet().size.requireEquals(events.size, "uniqueEventIds.size")
        return events.sortedBy { it.globalPosition }
    }
}
