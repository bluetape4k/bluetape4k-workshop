package io.bluetape4k.workshop.imageprocessing.advanced.web

import io.bluetape4k.images.spring.ImageStorageException
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ImageProcessingExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleValidation(exception: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid image upload")

    @ExceptionHandler(ImageStorageException::class)
    fun handleStorage(exception: ImageStorageException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.message ?: "Image storage failed")

    @ExceptionHandler(ImageAssetNotFoundException::class)
    fun handleAssetNotFound(exception: ImageAssetNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message ?: "Image asset not found")
}
