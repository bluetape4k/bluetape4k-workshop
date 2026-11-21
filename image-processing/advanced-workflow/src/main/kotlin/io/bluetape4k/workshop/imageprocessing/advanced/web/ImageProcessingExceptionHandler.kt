package io.bluetape4k.workshop.imageprocessing.advanced.web

import io.bluetape4k.images.spring.ImageStorageException
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
/**
 * ImageProcessingExceptionHandler은 이미지 처리 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ImageProcessingExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    /**
     * MIME, 크기, 치수와 같은 업로드 입력 계약 위반을 400으로 변환합니다.
     * 입력 검증 실패는 storage·libvips 작업 전에 종료되어야 합니다.
     *
     * 예: 지원하지 않는 content type은 `ProblemDetail.status == 400`으로 반환됩니다.
     */
    fun handleValidation(exception: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid image upload")

    @ExceptionHandler(ImageStorageException::class)
    /**
     * 원본 또는 variant 저장 실패를 503으로 매핑합니다. asset row를 성공으로
     * 확정하지 않고 cleanup/retry가 가능한 상태로 남기는 것이 핵심 업무 규칙입니다.
     *
     * 예: S3 장애 중에는 `READY` asset을 만들지 않습니다.
     */
    fun handleStorage(exception: ImageStorageException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.message ?: "Image storage failed")

    @ExceptionHandler(ImageAssetNotFoundException::class)
    /**
     * 요청한 asset이 없거나 caller scope에서 보이지 않을 때 404를 반환합니다.
     * 존재 여부를 외부에 과도하게 노출하지 않도록 원본 storage key는 detail에 넣지 않습니다.
     *
     * 예: 삭제된 checksum을 다시 조회하면 `ProblemDetail.status == 404`입니다.
     */
    fun handleAssetNotFound(exception: ImageAssetNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message ?: "Image asset not found")
}
