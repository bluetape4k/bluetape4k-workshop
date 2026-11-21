package io.bluetape4k.workshop.imageprocessing.advanced.model

/**
 * `image_objects` 행의 구분자입니다. 원본과 파생 객체를 구분해야 조회
 * 응답에서 private/public 저장 경계를 잘못 조립하지 않을 수 있습니다.
 *
 * ## 종류
 * - [ORIGINAL] — 클라이언트가 업로드한 원본 이미지입니다. 파일명과 원본
 *   크기를 보존하지만 public URL 정책은 별도로 결정해야 합니다.
 * - [VARIANT] — 처리 중 생성한 파생 이미지입니다(예: thumbnail, webp, 2x).
 *   화면 전달을 위한 크기/형식이며 원본을 대체하지 않습니다.
 * ```kotlin
 * val kind = if (variantName == null) ImageObjectKind.ORIGINAL else ImageObjectKind.VARIANT
 * ```
 */
enum class ImageObjectKind {
    /** 클라이언트가 올린 원본이며 private 접근 정책을 적용합니다. */
    /** 클라이언트가 올린 원본이며 private 접근 정책을 적용합니다. */
    ORIGINAL,
    /** thumbnail/webp 등 파생 객체이며 public 전달 정책을 별도로 적용합니다. */
    /** thumbnail/webp 등 파생 객체이며 public 전달 정책을 별도로 적용합니다. */
    VARIANT,
}
