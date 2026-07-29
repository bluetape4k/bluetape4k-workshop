package io.bluetape4k.workshop.messaging.outbox.outbox

/**
 * [OutboxEvent] 의 processing status 를 표현합니다.
 *
 * ## States
 * - [PENDING] — event 가 created 되었지만 아직 Kafka 로 published 되지 않았습니다.
 * - [PUBLISHED] — event 가 Kafka 로 successfully sent 되었습니다.
 * - [FAILED] — 마지막 publish attempt 가 실패했으며 retry 대상입니다.
 * - [DEAD_LETTER] — [OutboxPublisher.MAX_RETRY] 를 초과했으며 더 이상 retry 하지 않습니다.
 */
enum class OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
    DEAD_LETTER,
}
