package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.images.barcode.BarcodeException
import io.bluetape4k.images.barcode.BarcodeFailureReason
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import java.util.Locale

@RestControllerAdvice
internal class BarcodeApiExceptionHandler {

    @ExceptionHandler(BarcodeRequestException::class)
    fun handleRequest(exception: BarcodeRequestException): ResponseEntity<BarcodeErrorResponse> =
        ResponseEntity.status(exception.status).body(
            BarcodeErrorResponse(exception.error, message = exception.message ?: "Invalid barcode request.")
        )

    @ExceptionHandler(MaxUploadSizeExceededException::class)
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
    fun handleBarcode(exception: BarcodeException): ResponseEntity<BarcodeErrorResponse> {
        val status = when (exception.reason) {
            BarcodeFailureReason.MALFORMED_INPUT,
            BarcodeFailureReason.UNSUPPORTED_FORMAT -> HttpStatus.BAD_REQUEST

            BarcodeFailureReason.PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        val message = when (exception.reason) {
            BarcodeFailureReason.MALFORMED_INPUT -> "The uploaded file is not a decodable image."
            BarcodeFailureReason.UNSUPPORTED_FORMAT -> "The requested barcode format is not supported."
            BarcodeFailureReason.PROVIDER_UNAVAILABLE -> "The barcode provider is unavailable."
            else -> "Barcode extraction failed."
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
