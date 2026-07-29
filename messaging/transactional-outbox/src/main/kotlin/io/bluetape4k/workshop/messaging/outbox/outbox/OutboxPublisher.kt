package io.bluetape4k.workshop.messaging.outbox.outbox

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.messaging.outbox.domain.OutboxEventTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * [OutboxEventTable] 에서 [OutboxStatus.PENDING] 및 [OutboxStatus.FAILED] event 를 polling 하고 Kafka 로 publish 하는 scheduled background component 입니다.
 *
 * ## Retry semantics
 * publish attempt 가 실패하면 [retryCount] 는 **별도** `REQUIRES_NEW` transaction 에서 증가하므로 outer transaction rollback 뒤에도 counter 가 유지됩니다. [retryCount] 가 [MAX_RETRY] 에 도달하면 event 는 [OutboxStatus.DEAD_LETTER] 로 transition 합니다.
 *
 * ## Idempotency
 * event 가 publish 가능한 상태([OutboxStatus.PENDING] 또는 [OutboxStatus.FAILED])가 아니면 [publishEvent] 는 즉시 `false` 를 반환합니다. 따라서 이미 published 된 같은 event 에 두 번 호출해도 안전합니다.
 */
@Component
class OutboxPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    companion object : KLogging() {
        const val TOPIC = "order-events"
        const val MAX_RETRY = 3
    }

    /**
     * 2초마다 publish 가능한 outbox event 를 polling 하고 각 event 를 publish 합니다.
     *
     * batch 를 load 하기 위해 read-only transaction 안에서 실행됩니다. 각 event 는 자체 transaction 을 관리하는 [publishEvent] 를 통해 publish 됩니다.
     */
    @Scheduled(fixedDelay = 2000)
    @Transactional(readOnly = true)
    fun publishPendingEvents() {
        val pendingIds = OutboxEventTable.selectAll()
            .where {
                ((OutboxEventTable.status eq OutboxStatus.PENDING) or
                    (OutboxEventTable.status eq OutboxStatus.FAILED)) and
                    (OutboxEventTable.retryCount less MAX_RETRY)
            }
            .map { it[OutboxEventTable.id].value }

        log.debug { "publishPendingEvents: found ${pendingIds.size} publishable event(s)" }

        pendingIds.forEach { id ->
            try {
                publishEvent(id)
            } catch (e: Exception) {
                log.warn(e) { "Unexpected error while publishing outbox event id=$id" }
            }
        }
    }

    /**
     * [eventId] 로 식별되는 단일 outbox event 를 publish 합니다.
     *
     * Kafka send 는 database status update **전에** 수행됩니다. send 가 throw 하면 주변 transaction 이 rollback 되고 row 는 다음 poll 을 위해 PENDING/FAILED 상태로 남습니다. failure 시 retry-counter increment 가 항상 독립적으로 persist 되도록 [REQUIRES_NEW] transaction 을 사용합니다.
     *
     * @return event 가 성공적으로 sent 되고 [OutboxStatus.PUBLISHED] 로 표시되면 `true` 입니다. event 가 이미 published 된 경우, 즉 idempotent duplicate call 이면 `false` 입니다.
     */
    @Transactional
    fun publishEvent(eventId: Long): Boolean {
        val row = OutboxEventTable.selectAll()
            .where { OutboxEventTable.id eq eventId }
            .singleOrNull()
            ?: return false

        val currentStatus = row[OutboxEventTable.status]
        if (currentStatus != OutboxStatus.PENDING && currentStatus != OutboxStatus.FAILED) {
            log.debug { "Event id=$eventId is already in state=$currentStatus — skipping" }
            return false
        }

        val currentRetry = row[OutboxEventTable.retryCount]
        if (currentRetry >= MAX_RETRY) {
            markDeadLetter(eventId)
            return false
        }

        val aggregateId = row[OutboxEventTable.aggregateId]
        val payload = row[OutboxEventTable.payload]

        return try {
            // status 를 update 하기 전에 send 성공을 알 수 있도록 synchronous get() 을 사용합니다.
            kafkaTemplate.send(TOPIC, aggregateId, payload).get()
            markPublished(eventId)
            log.debug { "Published outbox event id=$eventId to topic=$TOPIC" }
            true
        } catch (e: Exception) {
            log.error(e) { "Failed to publish outbox event id=$eventId" }
            val newRetry = currentRetry + 1
            incrementRetry(eventId, newRetry)
            false
        }
    }

    // ── private status-update helper 입니다. 각각 자체 REQUIRES_NEW tx 에서 실행됩니다. ────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markPublished(eventId: Long) {
        OutboxEventTable.update({ OutboxEventTable.id eq eventId }) {
            it[OutboxEventTable.status] = OutboxStatus.PUBLISHED
            it[OutboxEventTable.processedAt] = LocalDateTime.now()
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun incrementRetry(eventId: Long, newRetryCount: Int) {
        val newStatus = if (newRetryCount >= MAX_RETRY) OutboxStatus.DEAD_LETTER else OutboxStatus.FAILED
        OutboxEventTable.update({ OutboxEventTable.id eq eventId }) {
            it[OutboxEventTable.retryCount] = newRetryCount
            it[OutboxEventTable.status] = newStatus
        }
        if (newStatus == OutboxStatus.DEAD_LETTER) {
            log.warn { "Outbox event id=$eventId moved to DEAD_LETTER after $newRetryCount attempts" }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markDeadLetter(eventId: Long) {
        OutboxEventTable.update({ OutboxEventTable.id eq eventId }) {
            it[OutboxEventTable.status] = OutboxStatus.DEAD_LETTER
        }
    }
}
