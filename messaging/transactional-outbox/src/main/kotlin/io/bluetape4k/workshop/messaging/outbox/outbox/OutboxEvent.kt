package io.bluetape4k.workshop.messaging.outbox.outbox

import java.io.Serializable
import java.time.LocalDateTime

/**
 * [io.bluetape4k.workshop.messaging.outbox.domain.OutboxEventTable] row 의 DTO projection 입니다.
 *
 * [OutboxPublisher] 가 database 에서 Kafka 로 옮기는 값입니다.
 *
 * @property id `outbox_events` 의 primary key 입니다.
 * @property aggregateType domain type 입니다. 예: `"Order"`
 * @property aggregateId aggregate PK 의 string representation 입니다.
 * @property eventType discriminator string 입니다. 예: `"OrderPlaced"`
 * @property payload JSON-serialized event body 입니다.
 * @property status 현재 [OutboxStatus] 입니다.
 * @property retryCount failed publish attempt 수입니다.
 * @property createdAt row creation time 입니다.
 * @property processedAt successful publish time 입니다. 아직 publish 되지 않았으면 null 입니다.
 */
data class OutboxEvent(
    val id: Long,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
    val status: OutboxStatus,
    val retryCount: Int,
    val createdAt: LocalDateTime,
    val processedAt: LocalDateTime?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
