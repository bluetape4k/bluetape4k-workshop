package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageKeys
import org.springframework.stereotype.Component

@Component
/**
 * 비공개 원본과 공개 파생 이미지용 안전한 객체 스토리지 키를 만듭니다.
 */
class ProfileImageKeyFactory {

    fun validateUserId(userId: String): String {
        userId.requireNotBlank("userId")
        require(USER_ID_REGEX.matches(userId)) { "userId must match [A-Za-z0-9._-]{1,80}" }
        return userId
    }

    fun keys(userId: String, uploadId: String, filename: String?): ProfileImageKeys {
        val safeUserId = validateUserId(userId)
        uploadId.requireNotBlank("uploadId")
        return ProfileImageKeys(
            original = ImageObjectKey.of("profile-images/$safeUserId/$uploadId/private/original", sanitizeFilename(filename)),
            pending = ImageObjectKey.of("profile-images/$safeUserId/$uploadId/pending", "blurred.jpg"),
            approved = ImageObjectKey.of("profile-images/$safeUserId/$uploadId/public", "approved.jpg"),
        )
    }

    fun sanitizeFilename(filename: String?): String {
        val raw = filename
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_FILENAME
        val replaced = raw.map { char ->
            if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
        }.joinToString("")
        val normalized = replaced.trim('.', '_', '-').ifBlank { DEFAULT_FILENAME }
        return if (normalized.length <= MAX_FILENAME_LENGTH) normalized else normalized.take(MAX_FILENAME_LENGTH)
    }

    companion object {
        private val USER_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,80}$")
        private const val DEFAULT_FILENAME = "upload.jpg"
        private const val MAX_FILENAME_LENGTH = 120
    }
}
