package io.bluetape4k.workshop.imageprocessing.profile.web

import io.bluetape4k.images.spring.ImageStorageException
import kotlinx.coroutines.TimeoutCancellationException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
/**
 * Maps profile-image upload failures to ProblemDetail responses.
 */
class ProfileImageExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(exception: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid profile image request")

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleTooLarge(exception: MaxUploadSizeExceededException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(413), exception.message ?: "Profile image is too large")

    @ExceptionHandler(TimeoutCancellationException::class)
    fun handleTimeout(exception: TimeoutCancellationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.message ?: "Profile image processing timed out")

    @ExceptionHandler(ImageStorageException::class)
    fun handleStorage(exception: ImageStorageException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.message ?: "Profile image storage failed")
}
