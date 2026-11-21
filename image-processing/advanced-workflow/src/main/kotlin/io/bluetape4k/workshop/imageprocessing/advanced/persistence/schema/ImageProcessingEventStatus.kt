package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * 단일 [ImageProcessingEventTable] 행의 결과 상태입니다. 단계 이름과
 * 함께 읽어야 validation 실패와 의도적인 중복 단축 경로를 구분할 수 있습니다.
 *
 * ## 상태
 * - [COMPLETED] — 단계가 성공적으로 끝났습니다.
 * - [FAILED] — 단계에서 오류가 발생했습니다. 최종 job failure와 함께 원인을
 *   확인해야 합니다.
 * - [SKIPPED] — 단계를 의도적으로 우회했습니다(예: 중복 제거 단축 경로).
 */
enum class ImageProcessingEventStatus {
    /** 해당 파이프라인 단계가 성공적으로 끝났습니다. */
    /** 해당 파이프라인 단계가 성공적으로 끝났습니다. */
    COMPLETED,
    /** 단계가 실패했으며 최종 job failure와 원인을 함께 확인해야 합니다. */
    /** 단계가 실패했으며 최종 job failure와 원인을 함께 확인해야 합니다. */
    FAILED,
    /** 중복 제거 등 정책상 단계를 의도적으로 우회했습니다. */
    /** 중복 제거 등 정책상 단계를 의도적으로 우회했습니다. */
    SKIPPED,
}
