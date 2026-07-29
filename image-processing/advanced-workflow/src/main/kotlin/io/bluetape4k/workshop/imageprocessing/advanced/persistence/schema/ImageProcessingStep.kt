package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * [ImageProcessingEventTable]에 기록하는 처리 파이프라인 단계입니다.
 *
 * ## 단계(실행 순서)
 * - [VALIDATION]      — 입력 파일 검증(MIME 유형, 크기, 이미지 치수)
 * - [VIPS_PROCESSING] — libvips 이미지 크기 변경과 형식 변환
 * - [S3_UPLOAD]       — 원본 및 변형 객체를 S3에 업로드
 * - [JOB_COMPLETED]   — job 성공 시 쓰는 최종 마커
 * - [JOB_FAILED]      — job 실패 시 쓰는 최종 마커
 */
enum class ImageProcessingStep {
    VALIDATION,
    VIPS_PROCESSING,
    S3_UPLOAD,
    JOB_COMPLETED,
    JOB_FAILED,
}
