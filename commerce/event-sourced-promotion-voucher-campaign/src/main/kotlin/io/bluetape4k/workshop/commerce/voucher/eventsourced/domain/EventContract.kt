package io.bluetape4k.workshop.commerce.voucher.eventsourced.domain

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
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
        require(version >= 0) { "stream.version must be non-negative" }
    }
}

internal data class EventPayload(val canonicalJson: String) {
    init {
        canonicalJson.requireNotBlank("payload")
        require(payloadByteSize() <= MAX_EVENT_PAYLOAD_BYTES) {
            "payload exceeds $MAX_EVENT_PAYLOAD_BYTES bytes"
        }
        require(SENSITIVE_FIELD_NAMES.none(::containsField)) {
            "payload contains a raw sensitive field"
        }
        validateJsonBounds(canonicalJson)
    }

    private fun payloadByteSize(): Int = canonicalJson.toByteArray(StandardCharsets.UTF_8).size

    private fun containsField(fieldName: String): Boolean =
        canonicalJson.contains("\"$fieldName\"", ignoreCase = true)

    private companion object {
        val SENSITIVE_FIELD_NAMES =
            setOf("voucherCode", "email", "phone", "accessToken", "idempotencyKey")

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
                            require(stringBytes <= MAX_EVENT_PAYLOAD_STRING_BYTES) {
                                "payload string exceeds $MAX_EVENT_PAYLOAD_STRING_BYTES bytes"
                            }
                        }
                    }
                } else {
                    depth = updateDepth(char, depth)
                    if (char == '"') inString = true
                }
            }
            require(!inString && depth == 0) { "payload has unbalanced JSON structure" }
        }

        private fun updateDepth(char: Char, depth: Int): Int {
            val nextDepth =
                when (char) {
                    '{', '[' -> depth + 1
                    '}', ']' -> depth - 1
                    else -> depth
                }
            require(nextDepth >= 0) { "payload has unbalanced nesting" }
            require(nextDepth <= MAX_EVENT_PAYLOAD_DEPTH) {
                "payload exceeds depth $MAX_EVENT_PAYLOAD_DEPTH"
            }
            return nextDepth
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
) {
    val canonicalChecksum: String
        get() = checksumOf(canonicalFields())

    init {
        require(eventId.version() == UUID_V7) { "eventId must be UUID v7" }
        require(globalPosition > 0) { "globalPosition must be positive" }
        eventType.requireNotBlank("eventType")
        schemaVersion.requireInRange(1, Int.MAX_VALUE, "schemaVersion")
        correlationId.requireNotBlank("correlationId")
        actorSurrogate.requireNotBlank("actorSurrogate")
        require(!recordedAt.isBefore(occurredAt)) {
            "recordedAt must not precede occurredAt"
        }
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
        require(fromVersion > 0 && toVersion == fromVersion + 1) {
            "upcaster must advance exactly one schema version"
        }
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
        require(fromVersion > 0 && toVersion == fromVersion + 1) {
            "fixture must advance exactly one schema version"
        }
    }
}

internal data class EventSchema<T : Any>(
    val eventType: String,
    val currentVersion: Int,
    val decode: (EventPayload) -> T,
) {
    init {
        eventType.requireNotBlank("eventType")
        require(currentVersion > 0) { "currentVersion must be positive" }
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
        require(schemasByType.size == schemas.size) { "event schemas must be unique by type" }
        require(upcastersByStep.size == upcasters.size) { "upcasters must be unique by step" }
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
        require(events.map { it.eventId }.toSet().size == events.size) {
            "event identifiers must be unique"
        }
        return events.sortedBy { it.globalPosition }
    }
}
