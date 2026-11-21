package io.bluetape4k.workshop.imageprocessing.ocr.web

import io.bluetape4k.images.ocr.TiffMultiPageOcrException
import io.bluetape4k.images.ocr.TiffMultiPageOcrFailureReason
import io.bluetape4k.images.ocr.TiffMultiPageOcrValidationException
import io.bluetape4k.logging.KLogging
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

    private companion object: KLogging()

    /**
     * TIFF metadata/resource budget 거부를 원본 payload나 native 경로 없이 문제 상세로
     * 매핑합니다.
     *
     * 예:
     * ```kotlin
     * val problem = handleTiffValidation(TiffMultiPageOcrValidationException(...))
     * // problem.status == 400, problem.properties["reason"]는 운영 분류용 코드
     * ```
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
     *
     * 예:
     * ```kotlin
     * val problem = handleTiffFailure(TiffMultiPageOcrException(...))
     * // native stack trace 대신 sanitized detail과 phase만 외부에 노출
     * ```
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
     *
     * 예:
     * ```kotlin
     * val problem = handleValidation(IllegalArgumentException("unsupported content type"))
     * // 호출자는 detail을 보고 파일 형식 또는 크기를 수정
     * ```
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleValidation(exception: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            exception.message ?: "Invalid image OCR request"
        )

    /**
     * tiffProblem은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param detail 업무 처리에 필요한 detail 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param pageIndex 업무 처리에 필요한 pageIndex 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * 예: TIFF decoder 오류는 내부 예외를 노출하지 않고 재업로드 또는 지원 포맷 변경을 안내하는 problem response로 변환합니다.
     */
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

    /**
     * phase은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun TiffMultiPageOcrFailureReason.phase(): String = when (this) {
        TiffMultiPageOcrFailureReason.INPUT_TOO_LARGE    -> "input"
        TiffMultiPageOcrFailureReason.READER_UNAVAILABLE -> "reader"

        TiffMultiPageOcrFailureReason.UNSUPPORTED_FORMAT,
        TiffMultiPageOcrFailureReason.PAGE_COUNT_UNKNOWN,
        TiffMultiPageOcrFailureReason.PAGE_LIMIT_EXCEEDED,
        TiffMultiPageOcrFailureReason.DIMENSIONS_UNAVAILABLE,
        TiffMultiPageOcrFailureReason.SIDE_LIMIT_EXCEEDED,
        TiffMultiPageOcrFailureReason.PIXELS_PER_PAGE_LIMIT_EXCEEDED,
        TiffMultiPageOcrFailureReason.TOTAL_PIXELS_LIMIT_EXCEEDED,
        TiffMultiPageOcrFailureReason.METADATA_LIMIT_EXCEEDED,
                                                         -> "metadata"

        TiffMultiPageOcrFailureReason.DECODE_FAILED      -> "decode"
        TiffMultiPageOcrFailureReason.ENGINE_FAILED      -> "engine"
        TiffMultiPageOcrFailureReason.RESULT_LIMIT_EXCEEDED -> "result"
        TiffMultiPageOcrFailureReason.UNKNOWN            -> "unknown"
    }
}
