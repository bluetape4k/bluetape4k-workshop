package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * Processing pipeline step recorded in [ImageProcessingEventTable].
 *
 * ## Steps (in execution order)
 * - [VALIDATION]      — input file validation (MIME type, size, dimensions)
 * - [VIPS_PROCESSING] — libvips image resizing and format conversion
 * - [S3_UPLOAD]       — upload of original and variant objects to S3
 * - [JOB_COMPLETED]   — final marker written when the job succeeds
 * - [JOB_FAILED]      — final marker written when the job fails
 */
enum class ImageProcessingStep {
    VALIDATION,
    VIPS_PROCESSING,
    S3_UPLOAD,
    JOB_COMPLETED,
    JOB_FAILED,
}
