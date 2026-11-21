package io.bluetape4k.workshop.imageprocessing.ocr.web

import io.bluetape4k.images.ocr.OcrStructuredDetail
import io.bluetape4k.logging.KLogging
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
 * @property service 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property properties 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@RestController
@RequestMapping("/api/images/ocr")
class ImageOcrController(
    /**
     * `service`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: ImageOcrService,
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ImageOcrProperties,
) {

    /**
     * JPEG, PNG, WebP 또는 다중 페이지 TIFF multipart 파일과 선택적 Tesseract 언어 코드 및
     * 구조화 상세 수준을 받습니다. `language`는 반복 파라미터로 보낼 수 있고,
     * `structuredDetail`은 `PLAIN_TEXT`, `LINE`, `WORD` 중 하나입니다. 입력
     * byte/content type 제한은 service와 native engine보다 앞서 적용됩니다.
     *
     * ```bash
     * curl -F "file=@scan.png;type=image/png" \
     *   -F "language=eng" -F "structuredDetail=WORD" \
     *   http://localhost:8080/api/images/ocr
     * ```
     * 유효한 입력인데 native engine을 사용할 수 없으면 `200 OK`와
     * `status=UNAVAILABLE`을 반환합니다. 잘못된 upload는 `400`, TIFF
     * processing failure는 sanitized `ProblemDetail`로 매핑됩니다.
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

    companion object: KLogging() {
        /**
         * 이미지 처리의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
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
