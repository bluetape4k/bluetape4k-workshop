package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * [ImageAssetTable] 행의 생명주기 상태입니다.
 *
 * ## 상태
 * - [PROCESSING] — asset이 현재 처리 중입니다.
 * - [READY]      — 처리가 성공적으로 끝나 파생 객체를 사용할 수 있습니다.
 * - [FAILED]     — 처리가 실패했으며 asset은 재시도할 수 있습니다.
 */
enum class ImageAssetStatus {
    PROCESSING,
    READY,
    FAILED,
}
