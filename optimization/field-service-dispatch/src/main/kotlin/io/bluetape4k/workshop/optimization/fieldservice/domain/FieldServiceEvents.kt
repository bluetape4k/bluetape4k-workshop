package io.bluetape4k.workshop.optimization.fieldservice.domain

import java.security.MessageDigest

/** Field Service aggregate에서 append할 수 있는 닫힌 event 종류입니다. */
enum class FieldServiceEventType {
    VISIT_CREATED,
    VISIT_CANCELLED,
    VISIT_URGENT,
    VISIT_PINNED,
    VISIT_UNPINNED,
    WORKER_UNAVAILABLE,
    VISIT_NO_SHOW,
    TRAVEL_TIME_UPDATED,
    PLAN_REPLANNED,
    PLAN_APPROVED,
    ROUTE_CONFIRMED,
}

/** 저장된 event digest와 incoming digest의 idempotency 결과입니다. */
enum class EventDigestMatch {
    DUPLICATE,
    EVENT_KEY_REUSED,
}

/** canonical digest 비교를 담당하는 순수 helper입니다. */
object FieldServiceEvents {
    fun compare(stored: EventDigest, incoming: EventDigest): EventDigestMatch {
        val same = MessageDigest.isEqual(
            stored.value.toByteArray(Charsets.US_ASCII),
            incoming.value.toByteArray(Charsets.US_ASCII),
        )
        return if (same) EventDigestMatch.DUPLICATE else EventDigestMatch.EVENT_KEY_REUSED
    }
}
