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
 * Scheduled background component that polls [OutboxEventTable] for [OutboxStatus.PENDING]
 * (and [OutboxStatus.FAILED]) events and publishes them to Kafka.
 *
 * ## Retry semantics
 * On a failed publish attempt, [retryCount] is incremented in a **separate**
 * `REQUIRES_NEW` transaction so the counter survives the outer transaction rollback.
 * Once [retryCount] reaches [MAX_RETRY] the event transitions to [OutboxStatus.DEAD_LETTER].
 *
 * ## Idempotency
 * [publishEvent] returns `false` immediately when the event is not in a publishable
 * state ([OutboxStatus.PENDING] or [OutboxStatus.FAILED]), so calling it twice on the
 * same already-published event is safe.
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
     * Polls for publishable outbox events every 2 seconds and publishes each one.
     *
     * Runs inside a read-only transaction to load the batch; each event is published
     * via [publishEvent] which manages its own transaction.
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
     * Publishes a single outbox event identified by [eventId].
     *
     * The Kafka send is performed **before** the database status update; if the send
     * throws, the surrounding transaction rolls back and the row remains PENDING/FAILED
     * for the next poll.  A [REQUIRES_NEW] transaction is used so that a retry-counter
     * increment (on failure) is always persisted independently.
     *
     * @return `true` if the event was successfully sent and marked [OutboxStatus.PUBLISHED];
     *         `false` if the event was already published (idempotent duplicate call).
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
            // Synchronous get() so we know the send succeeded before updating status
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

    // ── Private status-update helpers (each runs in own REQUIRES_NEW tx) ────

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
