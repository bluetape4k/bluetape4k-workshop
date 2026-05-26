package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * Outcome status of a single [ImageProcessingEventTable] row.
 *
 * ## States
 * - [COMPLETED] — step finished successfully
 * - [FAILED]    — step encountered an error
 * - [SKIPPED]   — step was intentionally bypassed (e.g. dedup short-circuit)
 */
enum class ImageProcessingEventStatus {
    COMPLETED,
    FAILED,
    SKIPPED,
}
