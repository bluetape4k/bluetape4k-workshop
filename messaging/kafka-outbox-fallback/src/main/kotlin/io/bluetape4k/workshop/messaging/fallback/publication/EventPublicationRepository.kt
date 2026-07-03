package io.bluetape4k.workshop.messaging.fallback.publication

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.messaging.fallback.config.FallbackOutboxProperties
import io.bluetape4k.workshop.messaging.fallback.domain.OrderTable
import org.jetbrains.exposed.v1.core.NotExists
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.concat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Clock
import java.time.LocalDateTime

/**
 * Repository for durable fallback publication rows.
 */
@Repository
class EventPublicationRepository(
    private val properties: FallbackOutboxProperties,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun upsertNotPublished(
        event: OrderPlacedEvent,
        payload: String,
        directAttemptCount: Int,
        errorCode: String,
        errorSummary: String,
    ) {
        val now = LocalDateTime.now(clock)
        val existing = EventPublicationTable.selectAll()
            .where { EventPublicationTable.eventId eq event.eventId }
            .singleOrNull()

        if (existing == null) {
            EventPublicationTable.insert {
                it[eventId] = event.eventId
                it[aggregateType] = "Order"
                it[aggregateId] = event.orderId.toString()
                it[eventType] = "OrderPlaced"
                it[EventPublicationTable.payload] = payload
                it[status] = EventPublicationStatus.NOT_PUBLISHED
                it[EventPublicationTable.directAttemptCount] = directAttemptCount
                it[relayRetryCount] = 0
                it[lastErrorCode] = errorCode
                it[lastErrorSummary] = sanitize(errorSummary)
                it[nextAttemptAt] = now
                it[updatedAt] = now
            }
        } else {
            EventPublicationTable.update({ EventPublicationTable.eventId eq event.eventId }) {
                it[EventPublicationTable.payload] = payload
                it[status] = EventPublicationStatus.NOT_PUBLISHED
                it[EventPublicationTable.directAttemptCount] = directAttemptCount
                it[relayRetryCount] = 0
                it[lastErrorCode] = errorCode
                it[lastErrorSummary] = sanitize(errorSummary)
                it[nextAttemptAt] = now
                it[claimedBy] = null
                it[claimedUntil] = null
                it[updatedAt] = now
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun upsertReconstructed(event: OrderPlacedEvent, payload: String): Boolean {
        val now = LocalDateTime.now(clock)
        val existing = EventPublicationTable.selectAll()
            .where { EventPublicationTable.eventId eq event.eventId }
            .singleOrNull()

        if (existing != null) {
            return false
        }

        EventPublicationTable.insert {
            it[eventId] = event.eventId
            it[aggregateType] = "Order"
            it[aggregateId] = event.orderId.toString()
            it[eventType] = "OrderPlaced"
            it[EventPublicationTable.payload] = payload
            it[status] = EventPublicationStatus.NOT_PUBLISHED
            it[directAttemptCount] = properties.directPublishAttempts
            it[relayRetryCount] = 0
            it[lastErrorCode] = "RECONSTRUCTED"
            it[lastErrorSummary] = "Reconstructed after direct publish fallback persistence failure"
            it[nextAttemptAt] = now
            it[updatedAt] = now
        }
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimNextBatch(claimedBy: String, batchSize: Int): List<EventPublicationRecord> {
        val now = LocalDateTime.now(clock)
        val claimUntil = now.plus(properties.relayClaimTtl)
        val candidates = EventPublicationTable.selectAll()
            .where { eligibleForClaim(now) }
            .orderBy(EventPublicationTable.nextAttemptAt to SortOrder.ASC, EventPublicationTable.id to SortOrder.ASC)
            .limit(batchSize)

        return candidates.mapNotNull { row ->
            val rowId = row[EventPublicationTable.id]
            val updated = EventPublicationTable.update({
                (EventPublicationTable.id eq rowId) and eligibleForClaim(now)
            }) {
                it[EventPublicationTable.claimedBy] = claimedBy
                it[EventPublicationTable.claimedUntil] = claimUntil
                it[updatedAt] = now
            }
            if (updated == 1) {
                toRecord(row, claimedByOverride = claimedBy, claimedUntilOverride = claimUntil)
            } else {
                null
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markPublished(eventId: String, claimedBy: String): Boolean {
        claimedBy.requireNotBlank("claimedBy")
        val now = LocalDateTime.now(clock)
        val updated = EventPublicationTable.update({
            (EventPublicationTable.eventId eq eventId) and (EventPublicationTable.claimedBy eq claimedBy)
        }) {
            it[status] = EventPublicationStatus.PUBLISHED
            it[publishedAt] = now
            it[EventPublicationTable.claimedBy] = null
            it[claimedUntil] = null
            it[lastErrorCode] = null
            it[lastErrorSummary] = null
            it[updatedAt] = now
        }
        return updated == 1
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markRelayFailure(
        eventId: String,
        claimedBy: String,
        retryCount: Int,
        errorCode: String,
        errorSummary: String,
    ): EventPublicationStatus {
        claimedBy.requireNotBlank("claimedBy")
        val now = LocalDateTime.now(clock)
        val nextStatus = if (retryCount >= properties.relayMaxRetries) {
            EventPublicationStatus.DEAD_LETTER
        } else {
            EventPublicationStatus.FAILED
        }
        val updated = EventPublicationTable.update({
            (EventPublicationTable.eventId eq eventId) and (EventPublicationTable.claimedBy eq claimedBy)
        }) {
            it[status] = nextStatus
            it[relayRetryCount] = retryCount
            it[lastErrorCode] = errorCode
            it[lastErrorSummary] = sanitize(errorSummary)
            it[nextAttemptAt] = now.plus(properties.relayFixedDelay)
            it[EventPublicationTable.claimedBy] = null
            it[claimedUntil] = null
            it[updatedAt] = now
        }
        return if (updated == 1) nextStatus else EventPublicationStatus.FAILED
    }

    @Transactional(readOnly = true)
    fun findAll(): List<EventPublicationRecord> =
        EventPublicationTable.selectAll().map { row -> toRecord(row) }

    /**
     * Finds old order events that still have no durable publication row.
     *
     * The cutoff and missing-row check stay in SQL so the reconciler does not
     * pull broad order sets into memory before filtering.
     */
    @Transactional(readOnly = true)
    fun findOrdersWithoutPublicationsCreatedOnOrBefore(cutoff: LocalDateTime): List<OrderPlacedEvent> {
        val eventIdExpression = concat(
            stringLiteral("order-placed:"),
            OrderTable.id.castTo(TextColumnType()),
            stringLiteral(":v1"),
        )
        val missingPublication = NotExists(
            EventPublicationTable
                .select(EventPublicationTable.id)
                .where { EventPublicationTable.eventId eq eventIdExpression },
        )

        return OrderTable.selectAll()
            .where {
                (OrderTable.createdAt lessEq cutoff) and missingPublication
            }
            .orderBy(OrderTable.createdAt to SortOrder.ASC, OrderTable.id to SortOrder.ASC)
            .map { row ->
                OrderPlacedEvent(
                    orderId = row[OrderTable.id].value,
                    customerId = row[OrderTable.customerId],
                    product = row[OrderTable.product],
                    quantity = row[OrderTable.quantity],
                    status = row[OrderTable.status],
                    createdAt = row[OrderTable.createdAt],
                )
            }
    }

    private fun toRecord(
        row: ResultRow,
        claimedByOverride: String? = null,
        claimedUntilOverride: LocalDateTime? = null,
    ): EventPublicationRecord =
        EventPublicationRecord(
            id = row[EventPublicationTable.id].value,
            eventId = row[EventPublicationTable.eventId],
            aggregateType = row[EventPublicationTable.aggregateType],
            aggregateId = row[EventPublicationTable.aggregateId],
            eventType = row[EventPublicationTable.eventType],
            payload = row[EventPublicationTable.payload],
            status = row[EventPublicationTable.status],
            directAttemptCount = row[EventPublicationTable.directAttemptCount],
            relayRetryCount = row[EventPublicationTable.relayRetryCount],
            lastErrorCode = row[EventPublicationTable.lastErrorCode],
            lastErrorSummary = row[EventPublicationTable.lastErrorSummary],
            nextAttemptAt = row[EventPublicationTable.nextAttemptAt],
            claimedBy = claimedByOverride ?: row[EventPublicationTable.claimedBy],
            claimedUntil = claimedUntilOverride ?: row[EventPublicationTable.claimedUntil],
            createdAt = row[EventPublicationTable.createdAt],
            publishedAt = row[EventPublicationTable.publishedAt],
            updatedAt = row[EventPublicationTable.updatedAt],
        )

    private fun sanitize(message: String): String =
        message
            .replace(Regex("(?i)(secret|token|password|credential)[^\\s,;]*"), "[redacted]")
            .take(240)

    private fun eligibleForClaim(now: LocalDateTime): Op<Boolean> {
        val retryableStatus =
            (EventPublicationTable.status eq EventPublicationStatus.NOT_PUBLISHED) or
                (EventPublicationTable.status eq EventPublicationStatus.FAILED)
        val claimExpired =
            EventPublicationTable.claimedUntil.isNull() or
                (EventPublicationTable.claimedUntil less now)
        return retryableStatus and (EventPublicationTable.nextAttemptAt lessEq now) and claimExpired
    }
}

/**
 * Immutable projection of a fallback publication row.
 */
data class EventPublicationRecord(
    val id: Long,
    val eventId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
    val status: EventPublicationStatus,
    val directAttemptCount: Int,
    val relayRetryCount: Int,
    val lastErrorCode: String?,
    val lastErrorSummary: String?,
    val nextAttemptAt: LocalDateTime,
    val claimedBy: String?,
    val claimedUntil: LocalDateTime?,
    val createdAt: LocalDateTime,
    val publishedAt: LocalDateTime?,
    val updatedAt: LocalDateTime,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
