package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.exposed.jdbc.repository.LongAuditableJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventDisposition
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository

@Repository
internal class ProviderEventInboxRepository :
    LongAuditableJdbcRepository<ProviderEventRecord, ProviderEventInboxTable> {
    override val table = ProviderEventInboxTable

    override fun extractId(entity: ProviderEventRecord) = entity.id

    override fun ResultRow.toEntity() =
        ProviderEventRecord(
            id = this[table.id].value,
            provider = this[table.provider],
            providerEventId = this[table.providerEventId],
            paymentAttemptId = this[table.paymentAttemptId],
            payloadFingerprint = this[table.payloadFingerprint],
            eventKind = this[table.eventKind],
            disposition = this[table.disposition],
            providerOccurredAt = this[table.providerOccurredAt],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedBy = this[table.updatedBy],
            updatedAt = this[table.updatedAt]
        )

    fun record(record: ProviderEventRecord): ProviderEventDisposition {
        val inserted =
            table
                .insertIgnore {
                    it[provider] = record.provider.take(40)
                    it[providerEventId] = record.providerEventId.take(160)
                    it[paymentAttemptId] = record.paymentAttemptId
                    it[payloadFingerprint] = record.payloadFingerprint
                    it[eventKind] = record.eventKind
                    it[disposition] = record.disposition
                    it[providerOccurredAt] = record.providerOccurredAt
                }.insertedCount == 1
        if (inserted) return record.disposition

        val existing =
            find(record.provider, record.providerEventId)
                ?: error("provider event disappeared after unique conflict")
        return if (existing.payloadFingerprint == record.payloadFingerprint) {
            ProviderEventDisposition.DUPLICATE
        } else {
            updateDisposition(record.provider, record.providerEventId, ProviderEventDisposition.CONFLICT)
            log.warn {
                "provider_event_payload_conflict provider=${record.provider} " +
                    "providerEventId=${record.providerEventId} paymentAttemptId=${record.paymentAttemptId}"
            }
            ProviderEventDisposition.CONFLICT
        }
    }

    fun find(
        provider: String,
        providerEventId: String,
    ): ProviderEventRecord? =
        table
            .selectAll()
            .where { (table.provider eq provider) and (table.providerEventId eq providerEventId) }
            .firstOrNull()
            ?.let { with(this) { it.toEntity() } }

    fun countUnresolved(): Long =
        table
            .selectAll()
            .where {
                table.disposition inList
                    listOf(
                        ProviderEventDisposition.CONFLICT,
                        ProviderEventDisposition.UNRESOLVED
                    )
            }.count()

    fun updateDisposition(
        provider: String,
        providerEventId: String,
        disposition: ProviderEventDisposition,
    ): Boolean =
        table.update({ (table.provider eq provider) and (table.providerEventId eq providerEventId) }) {
            it[ProviderEventInboxTable.disposition] = disposition
        } == 1

    companion object : KLogging()
}
