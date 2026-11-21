package io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema

/**
 * [ImageProcessingEventTable]에 기록하는 처리 파이프라인 단계입니다.
 * history API에서 “어디까지 성공했는가”를 설명하는 업무 audit 순서입니다.
 *
 * ## 단계(실행 순서)
 * - [VALIDATION] — 입력 파일 검증(MIME 유형, 크기, 이미지 치수)
 * - [VIPS_PROCESSING] — libvips 이미지 크기 변경과 형식 변환
 * - [S3_UPLOAD] — 원본 및 변형 객체를 storage에 업로드합니다. 이름은
 *   기존 persistence schema와의 호환성을 위해 유지합니다.
 * - [JOB_COMPLETED] — job 성공 시 쓰는 최종 마커
 * - [JOB_FAILED] — job 실패 시 쓰는 최종 마커
 */
enum class ImageProcessingStep {
    /** MIME, 크기, 이미지 치수 등 입력 계약을 검증하는 단계입니다. */
    /** MIME, 크기, 이미지 치수 등 입력 계약을 검증하는 단계입니다. */
    VALIDATION,
    /** libvips로 크기 변경·형식 변환을 수행하는 단계입니다. */
    /** libvips로 크기 변경·형식 변환을 수행하는 단계입니다. */
    VIPS_PROCESSING,
    /** 원본과 변형 객체를 storage에 기록하는 단계입니다. */
    /** 원본과 변형 객체를 storage에 기록하는 단계입니다. */
    S3_UPLOAD,
    /** 모든 필수 단계가 끝나 job 성공을 기록하는 terminal marker입니다. */
    /** 모든 필수 단계가 끝나 job 성공을 기록하는 terminal marker입니다. */
    JOB_COMPLETED,
    /** 처리 실패와 cleanup 경계를 기록하는 terminal marker입니다. */
    /** 처리 실패와 cleanup 경계를 기록하는 terminal marker입니다. */
    JOB_FAILED,
}
