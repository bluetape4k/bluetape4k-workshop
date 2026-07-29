package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.exposed.core.auditable.Auditable
import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant

/** enqueue deduplication을 위해 안정적인 delivery id로 keying되는 durable outbox row입니다. */
internal object NotificationDeliveryTable : AuditableLongIdTable("reservation_notification_deliveries") {
    val deliveryId = varchar("delivery_id", 160).uniqueIndex()
    val channel = enumerationByName<NotificationChannel>("channel", 24)
    val templateCode = varchar("template_code", 80)
    val aggregateId = varchar("aggregate_id", 80)
    val status = enumerationByName<NotificationDeliveryStatus>("status", 24)
    val attemptCount = integer("attempt_count").default(0)
    val nextAttemptAt = timestamp("next_attempt_at").nullable()
    val claimOwner = varchar("claim_owner", 80).nullable()
    val claimUntil = timestamp("claim_until").nullable()
    val failureCode = enumerationByName<NotificationFailureCode>("failure_code", 32).nullable()

    init {
        index(false, status, nextAttemptAt, id)
    }
}

internal data class NotificationDeliveryRecord(
    val id: Long,
    val delivery: NotificationDelivery,
    override val createdBy: String = UserContext.DEFAULT_USERNAME,
    override val createdAt: Instant? = null,
    override val updatedBy: String? = null,
    override val updatedAt: Instant? = null,
) : Auditable,
    Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** provider effect가 발생하기 전에 caller의 PostgreSQL transaction에 notification intent를 저장합니다. */
@Repository
internal class NotificationDeliveryRepository :
    LongAuditableJdbcRepository<NotificationDeliveryRecord, NotificationDeliveryTable> {
    override val table: NotificationDeliveryTable = NotificationDeliveryTable

    override fun extractId(entity: NotificationDeliveryRecord): Long = entity.id

    override fun ResultRow.toEntity(): NotificationDeliveryRecord =
        NotificationDeliveryRecord(
            id = this[table.id].value,
            delivery =
                NotificationDelivery(
                    deliveryId = this[table.deliveryId],
                    channel = this[table.channel],
                    templateCode = this[table.templateCode],
                    aggregateId = this[table.aggregateId],
                    status = this[table.status],
                    attemptCount = this[table.attemptCount],
                    nextAttemptAt = this[table.nextAttemptAt],
                    claimOwner = this[table.claimOwner],
                    claimUntil = this[table.claimUntil],
                    failureCode = this[table.failureCode]
                ),
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun enqueue(
        request: NotificationRequest,
        now: Instant,
    ): NotificationDeliveryRecord {
        val inserted =
            table
                .insertIgnore {
                    it[deliveryId] = request.deliveryId
                    it[channel] = request.channel
                    it[templateCode] = request.templateCode
                    it[aggregateId] = request.aggregateId
                    it[status] = NotificationDeliveryStatus.PENDING
                    it[nextAttemptAt] = now
                }.insertedCount == 1
        log.debug { "notification_delivery_persisted inserted=$inserted" }
        return findByDeliveryId(request.deliveryId)
            ?: error("notification delivery disappeared after enqueue")
    }

    fun findByDeliveryId(deliveryId: String): NotificationDeliveryRecord? =
        table
            .selectAll()
            .where { table.deliveryId eq deliveryId }
            .singleOrNull()
            ?.let { with(this) { it.toEntity() } }

    companion object : KLogging()
}

/** schema bootstrap과 PostgreSQL fixture에서 사용하는 application-owned table reference입니다. */
internal val reservationNotificationDeliveryTable = NotificationDeliveryTable
