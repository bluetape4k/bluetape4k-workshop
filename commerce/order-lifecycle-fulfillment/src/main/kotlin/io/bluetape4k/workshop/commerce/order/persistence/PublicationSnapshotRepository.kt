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
 * event payload를 materialize하지 않고 publication operations data를 읽습니다.
 *
 * status와 completion presence로 group 처리해 Spring Modulith publication repository가 사용하는
 * incomplete-publication predicate를 보존하면서 결과를 status별 최대 두 row로 제한합니다.
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
