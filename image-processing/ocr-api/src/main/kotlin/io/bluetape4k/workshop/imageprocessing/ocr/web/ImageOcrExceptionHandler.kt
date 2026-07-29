package io.bluetape4k.workshop.imageprocessing.ocr.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * OCR 요청 검증 실패를 RFC 7807 문제 상세로 매핑합니다.
 */
@RestControllerAdvice
class ImageOcrExceptionHandler {

    /**
     * 잘못된 multipart 또는 이미지 검증 입력을 `400 Bad Request`로 변환합니다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleValidation(exception: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid image OCR request")
}
