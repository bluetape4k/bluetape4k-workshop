package io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingEventDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.mapper.toImageProcessingEventDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventTable
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Repository

/**
 * Repository for [ImageProcessingEventTable] rows.
 *
 * ## Behavior / Contract
 * - All methods must be called inside an Exposed `transaction {}` or equivalent.
 * - [ImageProcessingEventTable.createdAt] is set automatically via the DB default (`CURRENT_TIMESTAMP`).
 * - Exceptions from [appendEvent] propagate to the caller — no suppression inside this method.
 */
@Repository
class ImageProcessingEventRepository : LongJdbcRepository<ImageProcessingEventDTO> {

    override val table = ImageProcessingEventTable

    override fun extractId(entity: ImageProcessingEventDTO): Long = entity.id

    override fun ResultRow.toEntity(): ImageProcessingEventDTO = toImageProcessingEventDTO()

    /**
     * Appends a single processing event row for the given [jobId].
     *
     * [ImageProcessingEventTable.createdAt] is populated by the DB default.
     * Exceptions propagate to the caller — no suppression is applied.
     *
     * @param jobId the parent job's primary key
     * @param step the pipeline step this event describes
     * @param status the outcome of the step
     * @param message optional human-readable description
     * @param payload optional structured diagnostic data; null when empty
     */
    fun appendEvent(
        jobId: Long,
        step: ImageProcessingStep,
        status: ImageProcessingEventStatus,
        message: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        ImageProcessingEventTable.insert {
            it[ImageProcessingEventTable.jobId] = jobId
            it[ImageProcessingEventTable.step] = step
            it[ImageProcessingEventTable.status] = status
            it[ImageProcessingEventTable.message] = message
            it[payloadJson] = payload.ifEmpty { null }
        }
    }

    /**
     * Returns all events for the job identified by [jobId], ordered by [ImageProcessingEventTable.createdAt] ASC.
     */
    fun findByJobId(jobId: Long): List<ImageProcessingEventDTO> =
        ImageProcessingEventTable.selectAll()
            .where { ImageProcessingEventTable.jobId eq jobId }
            .orderBy(ImageProcessingEventTable.createdAt, SortOrder.ASC)
            .map { it.toImageProcessingEventDTO() }
}
