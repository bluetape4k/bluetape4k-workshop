package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import org.springframework.stereotype.Component

@Component
/**
 * Validates declared profile-image metadata and magic bytes before storage.
 */
class UploadImageValidator(
    private val properties: ProfileImageModerationProperties,
) {

    fun validateDeclaredSize(sizeBytes: Long) {
        sizeBytes.requireInRange(0L, properties.maxInputBytes, "sizeBytes")
    }

    fun validate(contentType: String?, bytes: ByteArray): UploadOptions {
        bytes.size.requirePositiveNumber("bytes.size")
        validateDeclaredSize(bytes.size.toLong())
        val normalized = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        require(normalized in MAGIC_BYTES) { "unsupported image contentType: ${contentType ?: "<missing>"}" }
        require(MAGIC_BYTES.getValue(normalized)(bytes)) { "uploaded image bytes do not match contentType: $normalized" }
        return UploadOptions(contentType = normalized)
    }

    companion object {
        private val MAGIC_BYTES = mapOf<String, (ByteArray) -> Boolean>(
            "image/jpeg" to { bytes -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() },
            "image/png" to { bytes ->
                bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
                    bytes[3] == 0x47.toByte() && bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
                    bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
            },
        )
    }
}
