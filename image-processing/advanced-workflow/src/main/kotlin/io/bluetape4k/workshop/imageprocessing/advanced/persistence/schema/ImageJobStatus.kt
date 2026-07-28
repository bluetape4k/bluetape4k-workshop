package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * [ImageProcessingJobTable] 행의 실행 상태입니다.
 *
 * ## 상태
 * - [RUNNING]   — job이 현재 실행 중입니다.
 * - [SUCCEEDED] — job이 오류 없이 끝났습니다.
 * - [FAILED]    — job이 오류로 종료되었습니다.
 */
enum class ImageJobStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}
