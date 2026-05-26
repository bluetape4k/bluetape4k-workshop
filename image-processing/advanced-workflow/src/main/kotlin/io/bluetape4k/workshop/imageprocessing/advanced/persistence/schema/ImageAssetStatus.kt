package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * Lifecycle status of an [ImageAssetTable] row.
 *
 * ## States
 * - [PROCESSING] — asset is currently being processed
 * - [READY]      — processing completed successfully; derived objects are available
 * - [FAILED]     — processing failed; asset may be retried
 */
enum class ImageAssetStatus {
    PROCESSING,
    READY,
    FAILED,
}
