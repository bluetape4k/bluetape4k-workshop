package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.images.barcode.BarcodeException
import io.bluetape4k.images.barcode.BarcodeFailureReason
import io.bluetape4k.logging.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import java.util.*

@RestControllerAdvice
/**
 * BarcodeApiExceptionHandler은 이미지 처리 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class BarcodeApiExceptionHandler {

    private companion object: KLogging()

    @ExceptionHandler(BarcodeRequestException::class)
    /**
     * barcode 요청의 입력·권한·지원 포맷 오류를 예외에 담긴 status와 안정적인
     * error code로 반환합니다. 메시지는 caller가 입력을 수정할 수 있는 범위만 포함합니다.
     *
     * 예: 빈 파일은 `empty_input`, 지원하지 않는 format은 `unsupported_format`으로 분기합니다.
     */
    fun handleRequest(exception: BarcodeRequestException): ResponseEntity<BarcodeErrorResponse> =
        ResponseEntity.status(exception.status).body(
            BarcodeErrorResponse(exception.error, message = exception.message ?: "Invalid barcode request.")
        )

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    /**
     * multipart byte budget을 초과한 파일을 413으로 거절합니다. decoder를 실행하기
     * 전에 차단해 메모리와 native 처리 비용을 보호합니다.
     *
     * 예: 성공 응답 대신 `payload_too_large` 오류 body를 반환합니다.
     */
    fun handleMaxUploadSize(
        @Suppress("UNUSED_PARAMETER") exception: MaxUploadSizeExceededException,
    ): ResponseEntity<BarcodeErrorResponse> =
        ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(
            BarcodeErrorResponse(
                error = "payload_too_large",
                message = "The uploaded file exceeds the configured size limit.",
            )
        )

    @ExceptionHandler(MissingServletRequestPartException::class)
    /**
     * 계약된 multipart part가 없을 때 400과 `empty_input`을 반환합니다.
     * 파일이 없는 요청을 provider에 전달하지 않아 오류 원인을 안정적으로 분리합니다.
     *
     * 예: `file` part가 빠진 요청은 barcode decoder에 도달하지 않습니다.
     */
    fun handleMissingPart(
        @Suppress("UNUSED_PARAMETER") exception: MissingServletRequestPartException,
    ): ResponseEntity<BarcodeErrorResponse> =
        ResponseEntity.badRequest().body(
            BarcodeErrorResponse(
                error = "empty_input",
                message = "The multipart file part is required.",
            )
        )

    @ExceptionHandler(BarcodeException::class)
    /**
     * decoder의 failure reason을 입력 오류(400), provider 장애(503), 미분류 내부 오류로
     * 나눠 HTTP 계약으로 변환합니다. `reason`과 `message`는 관찰 가능성을 유지하되
     * 원본 payload나 stack trace를 노출하지 않습니다.
     *
     * 예: `MALFORMED_INPUT`은 재업로드를 유도하고 `PROVIDER_UNAVAILABLE`은 제한된 재시도를 유도합니다.
     */
    fun handleBarcode(exception: BarcodeException): ResponseEntity<BarcodeErrorResponse> {
        val status = when (exception.reason) {
            BarcodeFailureReason.MALFORMED_INPUT,
            BarcodeFailureReason.UNSUPPORTED_FORMAT,
                 -> HttpStatus.BAD_REQUEST

            BarcodeFailureReason.PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        val message = when (exception.reason) {
            BarcodeFailureReason.MALFORMED_INPUT    -> "The uploaded file is not a decodable image."
            BarcodeFailureReason.UNSUPPORTED_FORMAT -> "The requested barcode format is not supported."
            BarcodeFailureReason.PROVIDER_UNAVAILABLE -> "The barcode provider is unavailable."
            else                                    -> "Barcode extraction failed."
        }
        return ResponseEntity.status(status).body(
            BarcodeErrorResponse(
                error = exception.reason.name.lowercase(Locale.ROOT),
                reason = exception.reason.name,
                message = message,
            )
        )
    }
}
