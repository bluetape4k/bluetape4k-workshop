package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

import io.bluetape4k.exposed.core.jackson3.jacksonb
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Exposed table for individual pipeline step events within a processing job.
 *
 * Each row records one step outcome (validation, vips processing, S3 upload, etc.)
 * for a given [ImageProcessingJobTable] row.
 * Rows cascade-delete when the parent job is removed.
 */
object ImageProcessingEventTable : LongIdTable("image_processing_events") {

    /** FK to the parent [ImageProcessingJobTable]; cascades deletes. */
    val jobId = reference("job_id", ImageProcessingJobTable, onDelete = ReferenceOption.CASCADE)
        .index()

    /** Pipeline step this event describes. */
    val step = enumerationByName("step", 30, ImageProcessingStep::class)

    /** Outcome of this step. */
    val status = enumerationByName("status", 20, ImageProcessingEventStatus::class)

    /** Optional human-readable message (e.g. error description or summary). */
    val message = text("message").nullable()

    /** Optional structured JSON payload with step-specific diagnostic data. */
    val payloadJson = jacksonb<Map<String, Any?>>("payload_json").nullable()

    /** Wall-clock time when this event was recorded; defaults to DB `CURRENT_TIMESTAMP`. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}
