package io.bluetape4k.workshop.imageprocessing.advanced.model

/**
 * `image_objects` 행의 구분자입니다.
 *
 * ## 종류
 * - [ORIGINAL] — 클라이언트가 업로드한 원본 이미지입니다.
 * - [VARIANT]  — 처리 중 생성한 파생 이미지입니다(예: thumbnail, webp, 2x).
 */
enum class ImageObjectKind {
    ORIGINAL,
    VARIANT,
}
