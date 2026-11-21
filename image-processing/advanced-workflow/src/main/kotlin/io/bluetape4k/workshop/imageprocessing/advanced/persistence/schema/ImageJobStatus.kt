package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * [ImageProcessingJobTable] 행의 실행 상태입니다. 하나의 asset에 대한
 * 시도(attempt)를 표시하므로 asset 상태와 함께 읽어야 합니다.
 *
 * ## 상태
 * - [RUNNING] — job이 현재 실행 중입니다. 아직 최종 응답을 cache할 단계가 아닙니다.
 * - [SUCCEEDED] — job이 오류 없이 끝났고 완료 event가 기록되었습니다.
 * - [FAILED] — job이 오류로 종료되었고 failure event/정리 경로가 실행되었습니다.
 */
enum class ImageJobStatus {
    /** 하나의 asset 시도가 실행 중이며 최종 응답이나 cache를 확정하지 않습니다. */
    /** 하나의 asset 시도가 실행 중이며 최종 응답이나 cache를 확정하지 않습니다. */
    RUNNING,
    /** 시도가 성공했고 완료 event가 기록되어 결과를 읽을 수 있습니다. */
    /** 시도가 성공했고 완료 event가 기록되어 결과를 읽을 수 있습니다. */
    SUCCEEDED,
    /** 시도가 실패했고 failure event와 정리 경로를 확인해야 합니다. */
    /** 시도가 실패했고 failure event와 정리 경로를 확인해야 합니다. */
    FAILED,
}
