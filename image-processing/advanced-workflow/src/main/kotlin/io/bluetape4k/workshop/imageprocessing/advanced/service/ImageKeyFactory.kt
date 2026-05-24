package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Component

@Component
class ImageKeyFactory {

    fun originalKey(imageId: String, filename: String?): ImageObjectKey {
        imageId.requireNotBlank("imageId")
        return ImageObjectKey.of("images/$imageId/original", sanitizeFilename(filename))
    }

    fun variantKey(imageId: String, variantName: String, extension: String): ImageObjectKey {
        imageId.requireNotBlank("imageId")
        variantName.requireNotBlank("variantName")
        extension.requireNotBlank("extension")
        return ImageObjectKey.of("images/$imageId/variants", "$variantName.$extension")
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
        return if (normalized.length <= MAX_FILENAME_LENGTH) {
            normalized
        } else {
            val extension = normalized.substringAfterLast('.', "")
            val extensionSuffix = extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            if (extensionSuffix.length >= MAX_FILENAME_LENGTH) {
                normalized.take(MAX_FILENAME_LENGTH).trimEnd('.', '_', '-').ifBlank { DEFAULT_FILENAME }
            } else {
                normalized.take(MAX_FILENAME_LENGTH - extensionSuffix.length).trimEnd('.', '_', '-') + extensionSuffix
            }
        }
    }

    companion object {
        private const val DEFAULT_FILENAME = "upload.jpg"
        private const val MAX_FILENAME_LENGTH = 120
    }
}
