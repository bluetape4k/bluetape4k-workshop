package io.bluetape4k.workshop.imageprocessing.ocr.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps OCR request validation failures to RFC 7807 problem details.
 */
@RestControllerAdvice
class ImageOcrExceptionHandler {

    /**
     * Converts invalid multipart or image validation input into a `400 Bad Request`.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleValidation(exception: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid image OCR request")
}
