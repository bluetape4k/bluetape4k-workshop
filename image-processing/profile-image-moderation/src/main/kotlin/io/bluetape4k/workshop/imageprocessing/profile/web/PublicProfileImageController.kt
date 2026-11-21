package io.bluetape4k.workshop.imageprocessing.profile.web

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.storage.ImageStorage
import io.bluetape4k.logging.KLogging
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/public-images")
/**
 * 비공개 원본은 거부하고 대기 및 승인된 로컬 프로필 이미지 객체를 제공합니다.
 * @property storage 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class PublicProfileImageController(
    /**
     * `storage`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val storage: ImageStorage,
) {

    private companion object: KLogging()

    /**
     * pending/approved/default derivative만 제공합니다.
     *
     * ```bash
     * curl -i http://localhost:8080/public-images/profile-images/alice/upload-a/pending/blurred.jpg
     * curl -i
     * http://localhost:8080/public-images/profile-images/alice/upload-a/private/original.jpg
     * # 두 번째 요청은 404이며 private original을 공개하지 않음
     * ```
     * pending 결과에는 `Cache-Control: no-store`를 붙여 moderation 전 사용자
     * 콘텐츠가 오래 캐시되지 않게 합니다. 이 local controller는 인증을
     * 구현하지 않으므로 운영 public bucket/CDN 정책은 별도로 필요합니다.
     */
    @GetMapping("/{*path}")
    suspend fun get(@PathVariable path: String): ResponseEntity<ByteArray> {
        val normalized = path.trimStart('/')
        if (!isPublicProfileImagePath(normalized)) {
            return ResponseEntity.notFound().build()
        }
        val bytes = storage.download(
            ImageObjectKey(
                normalized.substringBeforeLast('/'),
                normalized.substringAfterLast('/')
            )
        )
        val builder = ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
        if (normalized.contains("/pending/")) {
            builder.cacheControl(CacheControl.noStore())
            builder.header(HttpHeaders.PRAGMA, "no-cache")
        }
        return builder.body(bytes)
    }

    /**
     * isPublicProfileImagePath은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와
     * 업무 오류를
     * 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param path 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun isPublicProfileImagePath(path: String): Boolean =
        path.startsWith("profile-images/") && !path.contains("/private/") &&
                (path.contains("/pending/") || path.contains("/public/") || path == "profile-images/default/default-profile.jpg")
}
