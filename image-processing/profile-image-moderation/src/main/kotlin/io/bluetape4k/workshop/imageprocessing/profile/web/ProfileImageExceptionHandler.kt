package io.bluetape4k.workshop.imageprocessing.profile.web

import io.bluetape4k.images.spring.ImageStorageException
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.TimeoutCancellationException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
/**
 * 프로필 이미지 업로드 실패를 ProblemDetail 응답으로 매핑합니다.
 */
class ProfileImageExceptionHandler {

    private companion object: KLogging()

    @ExceptionHandler(IllegalArgumentException::class)
    /**
     * 업로드 식별자·content type·이미지 정책 검증 실패를 400으로 변환합니다.
     * 원본 예외를 그대로 노출하지 않고 caller가 입력을 수정할 수 있는
     * ProblemDetail만 반환해야 합니다.
     *
     * 예: 잘못된 user/upload 조합은 저장소나 native 처리 전에 이 경계에서 거절됩니다.
     */
    fun handleBadRequest(exception: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message ?: "Invalid profile image request")

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    /**
     * multipart payload가 허용된 이미지 크기를 넘었을 때 413을 반환합니다.
     * 크기 제한은 메모리·native 변환 비용을 보호하는 업무 안전장치이므로
     * 재시도하더라도 동일 파일을 무제한으로 받아서는 안 됩니다.
     *
     * 예: 설정된 byte budget을 초과한 업로드는 processing queue에 넣지 않습니다.
     */
    fun handleTooLarge(exception: MaxUploadSizeExceededException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(413), exception.message ?: "Profile image is too large")

    @ExceptionHandler(TimeoutCancellationException::class)
    /**
     * privacy derivative 또는 moderation provider가 bounded timeout을 넘긴 경우
     * 503을 반환합니다. 이 결과는 원본 공개를 의미하지 않으며 상태를 fail closed로
     * 유지한 채 caller가 안전하게 재시도할 수 있는 계약입니다.
     *
     * 예: timeout 뒤에는 approved URL 대신 기본 이미지가 계속 유효합니다.
     */
    fun handleTimeout(exception: TimeoutCancellationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            exception.message ?: "Profile image processing timed out"
        )

    @ExceptionHandler(ImageStorageException::class)
    /**
     * 원본·pending·approved 객체 저장 실패를 503으로 매핑합니다. DB 상태와 object
     * storage 결과가 불일치할 수 있으므로 provider 원문 대신 정제된 detail만 반환하고
     * 보상/재시도 경로가 durable 상태를 다시 확인하도록 합니다.
     *
     * 예: S3 timeout은 성공으로 응답하지 않고 업로드 상태를 재conciliation 대상으로 남깁니다.
     */
    fun handleStorage(exception: ImageStorageException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            exception.message ?: "Profile image storage failed"
        )
}
