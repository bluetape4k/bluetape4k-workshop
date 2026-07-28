package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * 단일 [ImageProcessingEventTable] 행의 결과 상태입니다.
 *
 * ## 상태
 * - [COMPLETED] — 단계가 성공적으로 끝났습니다.
 * - [FAILED]    — 단계에서 오류가 발생했습니다.
 * - [SKIPPED]   — 단계를 의도적으로 우회했습니다(예: 중복 제거 단축 경로).
 */
enum class ImageProcessingEventStatus {
    COMPLETED,
    FAILED,
    SKIPPED,
}
