package io.bluetape4k.workshop.imageprocessing.ocr.web

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
 * Multipart HTTP adapter for OCR image recognition requests.
 */
@RestController
@RequestMapping("/api/images/ocr")
class ImageOcrController(
    private val service: ImageOcrService,
    private val properties: ImageOcrProperties,
) {

    /**
     * Accepts one JPEG, PNG, or WebP multipart file and optional Tesseract language codes.
     */
    @PostMapping(
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun recognize(
        @RequestPart("file") file: MultipartFile,
        @RequestParam("language", required = false) languages: List<String>?,
    ): ImageOcrResponse {
        file.size.requirePositiveNumber("file.size")
        require(file.size <= properties.maxUploadBytes) {
            "Image upload exceeds ${properties.maxUploadBytes} bytes"
        }
        require(file.contentType in SUPPORTED_CONTENT_TYPES) {
            "Unsupported image content type. Use JPEG, PNG, or WebP."
        }

        return service.recognize(
            ImageOcrRequest(
                bytes = file.bytes,
                contentType = file.contentType,
                languages = languages.orEmpty(),
            ),
        )
    }

    companion object {
        private val SUPPORTED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
        )
    }
}
