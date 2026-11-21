package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.images.ocr.OcrEngine

/**
 * native OCR이 활성화되었을 때 native OCR 엔진을 제공합니다.
 */
fun interface OcrEngineProvider {
    /**
     * get은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun get(): OcrEngine?
}
