package io.bluetape4k.workshop.commerce.order.persistence

import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.modulith.events.EventPublication
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi

internal data class PublicationStatusSummary(
    val published: Int,
    val processing: Int,
    val failed: Int,
    val resubmitted: Int,
    val completed: Int,
    val oldestIncomplete: Instant?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Reads publication operations data without materializing event payloads.
 *
 * Grouping by status and completion presence bounds the result to at most two rows per status while preserving the
 * incomplete-publication predicate used by Spring Modulith's publication repository.
 */
@Repository
@OptIn(ExperimentalUuidApi::class)
internal class PublicationSnapshotRepository(
    @param:Qualifier("eventPublicationTable")
    private val table: ExposedEventPublicationTable,
) {
    fun snapshot(): PublicationStatusSummary {
        val publicationCount = table.id.count()
        val oldestPublication = table.publicationDate.min()
        val completionMissing = table.completionDate.isNull()
        val counts =
            EventPublication.Status.entries
                .associateWith { 0L }
                .toMutableMap()
        var oldestIncomplete: Instant? = null

        table
            .select(table.status, completionMissing, publicationCount, oldestPublication)
            .groupBy(table.status, completionMissing)
            .forEach { row ->
                val status = row[table.status]?.let(EventPublication.Status::valueOf)
                if (status != null) counts[status] = counts.getValue(status) + row[publicationCount]

                if (row[completionMissing] || status != EventPublication.Status.COMPLETED) {
                    row[oldestPublication]?.let { candidate ->
                        if (oldestIncomplete == null || candidate < oldestIncomplete) oldestIncomplete = candidate
                    }
                }
            }

        return PublicationStatusSummary(
            published = counts.getValue(EventPublication.Status.PUBLISHED).toInt(),
            processing = counts.getValue(EventPublication.Status.PROCESSING).toInt(),
            failed = counts.getValue(EventPublication.Status.FAILED).toInt(),
            resubmitted = counts.getValue(EventPublication.Status.RESUBMITTED).toInt(),
            completed = counts.getValue(EventPublication.Status.COMPLETED).toInt(),
            oldestIncomplete = oldestIncomplete
        )
    }
}
