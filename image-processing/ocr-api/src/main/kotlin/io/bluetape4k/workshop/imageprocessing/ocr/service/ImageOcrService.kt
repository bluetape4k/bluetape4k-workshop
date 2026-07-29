package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrRequest
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrResponse

/**
 * OCR 워크숍 API용으로 업로드 이미지에서 텍스트를 인식합니다.
 */
interface ImageOcrService {

    /**
     * 텍스트를 인식하거나 구조화된 대체 응답을 반환합니다.
     */
    suspend fun recognize(request: ImageOcrRequest): ImageOcrResponse
}

/**
 * 테스트가 결정적 ID를 사용할 수 있도록 요청 ID를 제공합니다.
 */
fun interface RequestIdGenerator {
    fun nextId(): String
}
