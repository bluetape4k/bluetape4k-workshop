package io.bluetape4k.workshop.imageprocessing.profile.web

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.storage.ImageStorage
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
 */
class PublicProfileImageController(
    private val storage: ImageStorage,
) {

    @GetMapping("/{*path}")
    suspend fun get(@PathVariable path: String): ResponseEntity<ByteArray> {
        val normalized = path.trimStart('/')
        if (!isPublicProfileImagePath(normalized)) {
            return ResponseEntity.notFound().build()
        }
        val bytes = storage.download(ImageObjectKey.of(normalized.substringBeforeLast('/'), normalized.substringAfterLast('/')))
        val builder = ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
        if (normalized.contains("/pending/")) {
            builder.cacheControl(CacheControl.noStore())
            builder.header(HttpHeaders.PRAGMA, "no-cache")
        }
        return builder.body(bytes)
    }

    private fun isPublicProfileImagePath(path: String): Boolean =
        path.startsWith("profile-images/") && !path.contains("/private/") &&
            (path.contains("/pending/") || path.contains("/public/") || path == "profile-images/default/default-profile.jpg")
}
