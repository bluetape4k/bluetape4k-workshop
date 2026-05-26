package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * Execution status of an [ImageProcessingJobTable] row.
 *
 * ## States
 * - [RUNNING]   — job is currently executing
 * - [SUCCEEDED] — job finished without errors
 * - [FAILED]    — job terminated with an error
 */
enum class ImageJobStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}
