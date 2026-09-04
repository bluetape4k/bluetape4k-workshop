package io.bluetape4k.workshop.imageprocessing.ocr.web

import io.bluetape4k.images.ocr.TiffMultiPageOcrException
import io.bluetape4k.images.ocr.TiffMultiPageOcrFailureReason
import io.bluetape4k.images.ocr.TiffMultiPageOcrValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * OCR 요청 검증 실패를 RFC 7807 문제 상세로 매핑합니다.
 */
@RestControllerAdvice
class ImageOcrExceptionHandler {

    /**
     * TIFF metadata/resource budget 거부를 원본 payload나 native 경로 없이 문제 상세로
     * 매핑합니다.
     */
    @ExceptionHandler(TiffMultiPageOcrValidationException::class)
    fun handleTiffValidation(exception: TiffMultiPageOcrValidationException): ProblemDetail =
        tiffProblem(
            status = HttpStatus.BAD_REQUEST,
            detail = "TIFF OCR input was rejected.",
            reason = exception.reason,
            pageIndex = exception.pageIndex,
        )

    /**
     * TIFF decode/engine 단계 실패를 내부 구현 세부 정보 없이 `422`로 알립니다.
     */
    @ExceptionHandler(TiffMultiPageOcrException::class)
    fun handleTiffFailure(exception: TiffMultiPageOcrException): ProblemDetail =
        tiffProblem(
            status = HttpStatusCode.valueOf(422),
            detail = "TIFF OCR processing failed.",
            reason = exception.reason,
            pageIndex = exception.pageIndex,
        )

    /**
     * 잘못된 multipart 또는 이미지 검증 입력을 `400 Bad Request`로 변환합니다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleValidation(exception: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid image OCR request")

    private fun tiffProblem(
        status: HttpStatusCode,
        detail: String,
        reason: TiffMultiPageOcrFailureReason,
        pageIndex: Int?,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            setProperty("reason", reason.name)
            setProperty("phase", reason.phase())
            setProperty("pageIndex", pageIndex)
        }

    private fun TiffMultiPageOcrFailureReason.phase(): String = when (this) {
        TiffMultiPageOcrFailureReason.INPUT_TOO_LARGE -> "input"
        TiffMultiPageOcrFailureReason.READER_UNAVAILABLE -> "reader"
        TiffMultiPageOcrFailureReason.UNSUPPORTED_FORMAT,
        TiffMultiPageOcrFailureReason.PAGE_COUNT_UNKNOWN,
        TiffMultiPageOcrFailureReason.PAGE_LIMIT_EXCEEDED,
        TiffMultiPageOcrFailureReason.DIMENSIONS_UNAVAILABLE,
        TiffMultiPageOcrFailureReason.SIDE_LIMIT_EXCEEDED,
        TiffMultiPageOcrFailureReason.PIXELS_PER_PAGE_LIMIT_EXCEEDED,
        TiffMultiPageOcrFailureReason.TOTAL_PIXELS_LIMIT_EXCEEDED,
        TiffMultiPageOcrFailureReason.METADATA_LIMIT_EXCEEDED -> "metadata"
        TiffMultiPageOcrFailureReason.DECODE_FAILED -> "decode"
        TiffMultiPageOcrFailureReason.ENGINE_FAILED -> "engine"
        TiffMultiPageOcrFailureReason.RESULT_LIMIT_EXCEEDED -> "result"
        TiffMultiPageOcrFailureReason.UNKNOWN -> "unknown"
    }
}
