package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

import io.bluetape4k.exposed.core.jackson3.jacksonb
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Exposed table for image processing jobs.
 *
 * Each row tracks one processing run for an [ImageAssetTable] row.
 * Multiple jobs may exist per asset (e.g. after recovery from a [ImageJobStatus.FAILED] state).
 *
 * Uses plain [LongIdTable] — no auditing columns needed here; timing is tracked via
 * [startedAt] and [finishedAt].
 */
object ImageProcessingJobTable : LongIdTable("image_processing_jobs") {

    /** FK to the parent [ImageAssetTable]; cascades deletes. */
    val imageAssetId = reference("image_asset_id", ImageAssetTable, onDelete = ReferenceOption.CASCADE)
        .index()

    /** Current execution status of this job. */
    val status = enumerationByName("status", 20, ImageJobStatus::class)
        .default(ImageJobStatus.RUNNING)

    /** JSON array of variant names requested for this job (e.g. `["thumbnail", "webp-2x"]`). */
    val requestedVariants = jacksonb<List<String>>("requested_variants")

    /** Wall-clock time when the job started; defaults to DB `CURRENT_TIMESTAMP`. */
    val startedAt = timestamp("started_at").defaultExpression(CurrentTimestamp)

    /** Wall-clock time when the job finished; null while still running. */
    val finishedAt = timestamp("finished_at").nullable()

    /** Total processing duration in milliseconds; null while still running. */
    val durationMs = long("duration_ms").nullable()

    /** Short machine-readable error code (≤ 100 chars); null on success. */
    val errorCode = varchar("error_code", 100).nullable()

    /** Human-readable error description; null on success. */
    val errorMessage = text("error_message").nullable()
}
