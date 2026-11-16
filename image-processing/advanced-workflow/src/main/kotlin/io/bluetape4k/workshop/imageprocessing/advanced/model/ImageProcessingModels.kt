package io.bluetape4k.workshop.imageprocessing.advanced.model

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.support.hashOf
import java.io.Serializable

data class OriginalImageMetadata(
    val key: String,
    val url: String,
    val width: Int,
    val height: Int,
    val contentType: String,
    val sizeBytes: Long,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class ImageVariantMetadata(
    val name: String,
    val key: String,
    val url: String,
    val width: Int,
    val height: Int,
    val contentType: String,
    val sizeBytes: Long,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class ImageProcessingResponse(
    val imageId: String,
    val original: OriginalImageMetadata,
    val thumbnailUrl: String,
    val variants: List<ImageVariantMetadata>,
    val durationMillis: Long,
    val warnings: List<String> = emptyList(),
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class OriginalImageInfo(
    val width: Int,
    val height: Int,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class ProcessedImageVariant(
    val name: String,
    val key: ImageObjectKey,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val contentType: String,
): Serializable {
    val sizeBytes: Long get() = bytes.size.toLong()

    companion object {
        private const val serialVersionUID: Long = 1L
    }

    override fun equals(other: Any?): Boolean {
        return this === other ||
                other is ProcessedImageVariant &&
                name == other.name &&
                key == other.key &&
                bytes.contentEquals(other.bytes) &&
                width == other.width &&
                height == other.height &&
                contentType == other.contentType
    }

    override fun hashCode(): Int = hashOf(name, key, bytes.contentHashCode(), width, height, contentType)

    override fun toString(): String =
        ToStringBuilder(this)
            .add("name", name)
            .add("key", key)
            .add("bytesSize", bytes.size)
            .add("width", width)
            .add("height", height)
            .add("contentType", contentType)
            .toString()
}

data class ProcessedImageSet(
    val original: OriginalImageInfo,
    val variants: List<ProcessedImageVariant>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
