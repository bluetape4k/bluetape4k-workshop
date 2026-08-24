package io.bluetape4k.workshop.optimization.lastmile.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

enum class LastMileEventType {
    RETURN_REQUESTED,
    PICKUP_WINDOW_CHANGED,
    DRIVER_CHECKED_IN,
    VEHICLE_BROKEN_DOWN,
    CARRIER_CANCELLED,
    NO_SHOW,
    TRAFFIC_DURATION_UPDATED,
}

enum class EventDigestMatch {
    DUPLICATE,
    DIGEST_CONFLICT,
}

data class LastMileEvent(
    val eventId: EventId,
    val type: LastMileEventType,
    val aggregateId: String,
    val eventKey: String,
    val occurredAt: Instant,
    val canonicalPayload: String,
    val digest: String = sha256(canonicalPayload),
) {
    init {
        require(aggregateId.isNotBlank()) { "aggregate id must not be blank" }
        require(eventKey.matches(Regex("[A-Za-z0-9._:-]{1,96}"))) { "event key must be bounded" }
        require(canonicalPayload.length <= LastMileLimits.MAX_EVENT_PAYLOAD) { "event payload exceeds limit" }
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "event digest must be SHA-256" }
    }
}

object LastMileEventCanonicalizer {
    fun canonicalize(type: LastMileEventType, aggregateId: String, eventKey: String, payload: Map<String, String>): String {
        require(aggregateId.isNotBlank()) { "aggregate id must not be blank" }
        require(eventKey.matches(Regex("[A-Za-z0-9._:-]{1,96}"))) { "event key must be bounded" }
        return buildString {
            append(type.name)
            append('|')
            append(aggregateId)
            append('|')
            append(eventKey)
            payload.toSortedMap().forEach { (key, value) ->
                require(key.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "payload key must be bounded" }
                require(value.length <= 512) { "payload value exceeds limit" }
                append('|').append(key).append('=').append(value)
            }
        }
    }

    fun compare(stored: LastMileEvent, incoming: LastMileEvent): EventDigestMatch =
        if (MessageDigest.isEqual(
                stored.digest.toByteArray(StandardCharsets.US_ASCII),
                incoming.digest.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            EventDigestMatch.DUPLICATE
        } else {
            EventDigestMatch.DIGEST_CONFLICT
        }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
