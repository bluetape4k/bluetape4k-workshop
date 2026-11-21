package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrRequest
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrResponse

/**
 * OCR 워크숍 API용으로 업로드 이미지에서 텍스트를 인식합니다.
 *
 * 구현체는 native capability가 없어도 동일한 response shape을 유지해야 하며,
 * 원본 bytes나 OCR text를 log에 남겨서는 안 됩니다.
 */
interface ImageOcrService {

    /**
     * 텍스트를 인식하거나 구조화된 대체 응답을 반환합니다.
     *
     * [ImageOcrRequest.structuredDetail]이 `LINE`/`WORD`여도 engine이
     * [io.bluetape4k.images.ocr.StructuredOcrEngine]을 지원하지 않으면
     * plain-text로 fallback하고 [ImageOcrResponse.effectiveStructuredDetail]에
     * 실제 결과를 기록합니다. TIFF는 페이지 순서와 결과 budget을 지켜야 합니다.
     * ```kotlin
     * val response = service.recognize(request)
     * if (response.status == OcrStatus.UNAVAILABLE) {
     *     // native engine 설정 또는 재시도를 제품 정책으로 결정
     * }
     * ```
     */
    suspend fun recognize(request: ImageOcrRequest): ImageOcrResponse
}

/**
 * 테스트가 결정적 ID를 사용할 수 있도록 요청 ID를 제공합니다.
 */
fun interface RequestIdGenerator {
    /**
     * nextId은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun nextId(): String
}
