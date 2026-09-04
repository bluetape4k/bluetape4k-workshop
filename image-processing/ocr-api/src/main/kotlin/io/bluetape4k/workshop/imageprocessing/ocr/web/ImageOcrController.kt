package io.bluetape4k.workshop.imageprocessing.ocr.web

import io.bluetape4k.images.ocr.OcrStructuredDetail
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.imageprocessing.ocr.config.ImageOcrProperties
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrRequest
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrResponse
import io.bluetape4k.workshop.imageprocessing.ocr.service.ImageOcrService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * OCR 이미지 인식 요청용 multipart HTTP 어댑터입니다.
 */
@RestController
@RequestMapping("/api/images/ocr")
class ImageOcrController(
    private val service: ImageOcrService,
    private val properties: ImageOcrProperties,
) {

    /**
     * JPEG, PNG, WebP 또는 다중 페이지 TIFF multipart 파일과 선택적 Tesseract 언어 코드 및
     * 구조화 상세 수준을 받습니다.
     */
    @PostMapping(
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun recognize(
        @RequestPart("file") file: MultipartFile,
        @RequestParam("language", required = false) languages: List<String>?,
        @RequestParam("structuredDetail", required = false) structuredDetail: OcrStructuredDetail?,
    ): ImageOcrResponse {
        file.size.requirePositiveNumber("file.size")
        require(file.size <= properties.maxUploadBytes) {
            "Image upload exceeds ${properties.maxUploadBytes} bytes"
        }
        require(file.contentType in SUPPORTED_CONTENT_TYPES) {
            "Unsupported image content type. Use JPEG, PNG, WebP, or TIFF."
        }

        return service.recognize(
            ImageOcrRequest(
                bytes = file.bytes,
                contentType = file.contentType,
                languages = languages.orEmpty(),
                structuredDetail = structuredDetail ?: OcrStructuredDetail.PLAIN_TEXT,
            ),
        )
    }

    companion object {
        private val SUPPORTED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/tiff",
            "image/tif",
            "image/x-tiff",
        )
    }
}
