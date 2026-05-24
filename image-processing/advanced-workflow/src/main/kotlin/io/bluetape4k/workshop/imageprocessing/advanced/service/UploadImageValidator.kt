package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties
import org.springframework.stereotype.Component

@Component
class UploadImageValidator(
    private val properties: ImageProcessingAdvancedProperties,
) {

    fun validateDeclaredSize(sizeBytes: Long) {
        require(sizeBytes <= properties.maxInputBytes) {
            "uploaded image exceeds maxInputBytes (${properties.maxInputBytes}): $sizeBytes"
        }
    }

    fun validate(contentType: String?, bytes: ByteArray): UploadOptions {
        require(bytes.isNotEmpty()) { "uploaded image must not be empty" }
        validateDeclaredSize(bytes.size.toLong())
        val normalizedContentType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        require(normalizedContentType in MAGIC_BYTES) {
            "unsupported image contentType: ${contentType ?: "<missing>"}"
        }
        require(MAGIC_BYTES.getValue(normalizedContentType)(bytes)) {
            "uploaded image bytes do not match contentType: $normalizedContentType"
        }
        return UploadOptions(contentType = normalizedContentType)
    }

    companion object {
        private val MAGIC_BYTES: Map<String, (ByteArray) -> Boolean> = mapOf(
            "image/jpeg" to { bytes ->
                bytes.size >= 3 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte()
            },
            "image/png" to { bytes ->
                bytes.size >= 8 &&
                    bytes[0] == 0x89.toByte() &&
                    bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() &&
                    bytes[3] == 0x47.toByte() &&
                    bytes[4] == 0x0D.toByte() &&
                    bytes[5] == 0x0A.toByte() &&
                    bytes[6] == 0x1A.toByte() &&
                    bytes[7] == 0x0A.toByte()
            },
            "image/webp" to { bytes ->
                bytes.size >= 12 &&
                    bytes[0] == 'R'.code.toByte() &&
                    bytes[1] == 'I'.code.toByte() &&
                    bytes[2] == 'F'.code.toByte() &&
                    bytes[3] == 'F'.code.toByte() &&
                    bytes[8] == 'W'.code.toByte() &&
                    bytes[9] == 'E'.code.toByte() &&
                    bytes[10] == 'B'.code.toByte() &&
                    bytes[11] == 'P'.code.toByte()
            },
        )
    }
}
