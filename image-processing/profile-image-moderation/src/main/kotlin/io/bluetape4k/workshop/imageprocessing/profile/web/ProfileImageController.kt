package io.bluetape4k.workshop.imageprocessing.profile.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageView
import io.bluetape4k.workshop.imageprocessing.profile.service.ProfileImageService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/users/{userId}/profile-image")
/**
 * 사용자의 유효 프로필 이미지를 업로드하고 polling하는 REST API입니다.
 * @property service 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class ProfileImageController(
    /**
     * `service`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: ProfileImageService,
) {

    private companion object: KLogging()

    /**
     * 프로필 이미지 upload를 접수하고 moderation 전 pending view를 반환합니다.
     *
     * ```bash
     * curl -i -F file=@safe.jpg http://localhost:8080/api/users/alice/profile-image
     * ```
     * 성공 status는 `202 Accepted`이며, 응답의 `effectiveUrl`은 blurred
     * derivative입니다. 원본 private key는 API에 포함하지 않습니다.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun upload(
        @PathVariable userId: String,
        @RequestPart("file") file: MultipartFile,
    ): ProfileImageView = service.upload(userId, file)

    /**
     * 현재 profile image 상태를 polling합니다. 업로드가 없거나 반려/실패한
     * 경우에도 기본 이미지 URL을 반환하여 profile page가 안전한 fallback을
     * 렌더링할 수 있습니다.
     *
     * ```bash
     * curl http://localhost:8080/api/users/alice/profile-image
     * ```
     */
    @GetMapping
    fun find(@PathVariable userId: String): ProfileImageView = service.find(userId)
}
