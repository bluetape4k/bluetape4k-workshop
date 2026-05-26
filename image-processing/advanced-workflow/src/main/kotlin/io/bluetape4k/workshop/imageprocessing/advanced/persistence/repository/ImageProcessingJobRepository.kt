package io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageProcessingJobDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.mapper.toImageProcessingJobDTO
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageJobStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingJobTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository

/**
 * Repository for [ImageProcessingJobTable] rows.
 *
 * ## Behavior / Contract
 * - All methods must be called inside an Exposed `transaction {}` or equivalent.
 * - No auditing columns — timing is tracked via [ImageProcessingJobTable.startedAt]
 *   and [ImageProcessingJobTable.finishedAt].
 */
@Repository
class ImageProcessingJobRepository : LongJdbcRepository<ImageProcessingJobDTO> {

    override val table = ImageProcessingJobTable

    override fun extractId(entity: ImageProcessingJobDTO): Long = entity.id

    override fun ResultRow.toEntity(): ImageProcessingJobDTO = toImageProcessingJobDTO()

    /**
     * Inserts a new processing job for [assetId] with RUNNING status and returns the new job ID.
     *
     * [ImageProcessingJobTable.startedAt] is set by the DB default (`CURRENT_TIMESTAMP`).
     *
     * @param assetId the parent asset's primary key
     * @param requestedVariants variant names requested for this job (may be empty)
     * @return the generated job primary key
     */
    fun insertJob(assetId: Long, requestedVariants: List<String>): Long =
        ImageProcessingJobTable.insertAndGetId {
            it[ImageProcessingJobTable.imageAssetId] = assetId
            it[ImageProcessingJobTable.status] = ImageJobStatus.RUNNING
            it[ImageProcessingJobTable.requestedVariants] = requestedVariants
        }.value

    /**
     * Marks the job identified by [jobId] as SUCCEEDED.
     *
     * Sets [ImageProcessingJobTable.finishedAt] to `CURRENT_TIMESTAMP` and
     * stores [durationMs] for observability.
     */
    fun markSucceeded(jobId: Long, durationMs: Long) {
        ImageProcessingJobTable.update({ ImageProcessingJobTable.id eq jobId }) {
            it[status] = ImageJobStatus.SUCCEEDED
            it[finishedAt] = CurrentTimestamp
            it[ImageProcessingJobTable.durationMs] = durationMs
        }
    }

    /**
     * Marks the job identified by [jobId] as FAILED.
     *
     * Stores sanitized [errorCode] and [errorMessage] alongside the timing information.
     * Callers are responsible for sanitizing error strings before passing them in.
     */
    fun markFailed(jobId: Long, errorCode: String, errorMessage: String, durationMs: Long) {
        ImageProcessingJobTable.update({ ImageProcessingJobTable.id eq jobId }) {
            it[status] = ImageJobStatus.FAILED
            it[finishedAt] = CurrentTimestamp
            it[ImageProcessingJobTable.durationMs] = durationMs
            it[ImageProcessingJobTable.errorCode] = errorCode
            it[ImageProcessingJobTable.errorMessage] = errorMessage
        }
    }

    /**
     * Returns all jobs for the asset identified by [assetId], ordered by [ImageProcessingJobTable.startedAt] DESC.
     */
    fun findByAssetId(assetId: Long): List<ImageProcessingJobDTO> =
        ImageProcessingJobTable.selectAll()
            .where { ImageProcessingJobTable.imageAssetId eq assetId }
            .orderBy(ImageProcessingJobTable.startedAt, SortOrder.DESC)
            .map { it.toImageProcessingJobDTO() }
}
