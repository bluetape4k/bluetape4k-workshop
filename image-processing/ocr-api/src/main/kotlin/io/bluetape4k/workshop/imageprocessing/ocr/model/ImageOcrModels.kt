package io.bluetape4k.workshop.imageprocessing.ocr.model

import io.bluetape4k.images.ocr.OcrStructuredDetail
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
    val structuredDetail: OcrStructuredDetail = OcrStructuredDetail.PLAIN_TEXT,
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
    val effectiveStructuredDetail: OcrStructuredDetail = OcrStructuredDetail.PLAIN_TEXT,
    val pages: List<OcrPage> = emptyList(),
    val lines: List<OcrTextLine> = emptyList(),
    val words: List<OcrWord> = emptyList(),
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
    val pageIndex: Int? = null,
    val boundingBox: OcrBoundingBox? = null,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 2813846273462115403L
    }
}

/**
 * OCR 결과의 이미지 내 사각형 영역입니다.
 */
data class OcrBoundingBox(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -8542432708570251756L
    }
}

/**
 * 페이지 단위의 구조화된 OCR 결과입니다.
 */
data class OcrPage(
    val pageIndex: Int,
    val text: String,
    val confidence: Double?,
    val boundingBox: OcrBoundingBox? = null,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8658553843880049498L
    }
}

/**
 * 라인 단위의 구조화된 OCR 결과입니다.
 */
data class OcrTextLine(
    val pageIndex: Int,
    val text: String,
    val confidence: Double?,
    val boundingBox: OcrBoundingBox? = null,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 2574707970975838308L
    }
}

/**
 * 단어 단위의 구조화된 OCR 결과입니다.
 */
data class OcrWord(
    val pageIndex: Int,
    val text: String,
    val confidence: Double?,
    val boundingBox: OcrBoundingBox? = null,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -5814983464065807540L
    }
}
