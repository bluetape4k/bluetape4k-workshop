package io.bluetape4k.workshop.imageprocessing.ocr.model

import java.io.Serializable

/**
 * 워크숍 API가 반환하는 OCR 처리 상태입니다.
 */
enum class OcrStatus {
    COMPLETED,
    UNAVAILABLE,
    FAILED,
}

/**
 * 서비스 계층 OCR 요청입니다.
 */
data class ImageOcrRequest(
    val bytes: ByteArray,
    val contentType: String?,
    val languages: List<String>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -1524473156985272973L
    }
}

/**
 * 구조화된 OCR API 응답입니다.
 */
data class ImageOcrResponse(
    val requestId: String,
    val status: OcrStatus,
    val engine: String,
    val languages: List<String>,
    val confidence: Double?,
    val text: String,
    val blocks: List<OcrTextBlock>,
    val warnings: List<String>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -1667282267859767983L
    }
}

/**
 * 라인 기반 OCR 텍스트 블록입니다.
 */
data class OcrTextBlock(
    val index: Int,
    val text: String,
    val confidence: Double?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 2813846273462115403L
    }
}
