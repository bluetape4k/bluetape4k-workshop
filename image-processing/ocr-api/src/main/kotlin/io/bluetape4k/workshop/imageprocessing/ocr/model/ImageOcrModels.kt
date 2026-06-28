package io.bluetape4k.workshop.imageprocessing.ocr.model

import java.io.Serializable

/**
 * OCR processing status returned by the workshop API.
 */
enum class OcrStatus {
    COMPLETED,
    UNAVAILABLE,
    FAILED,
}

/**
 * Service-level OCR request.
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
 * Structured OCR API response.
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
 * Line-based OCR text block.
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
