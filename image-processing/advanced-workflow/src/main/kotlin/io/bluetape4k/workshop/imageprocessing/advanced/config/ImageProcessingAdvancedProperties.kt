package io.bluetape4k.workshop.imageprocessing.advanced.config

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

@ConfigurationProperties(prefix = "workshop.images.advanced")
data class ImageProcessingAdvancedProperties(
    val publicBaseUrl: String = "http://localhost:8080/public-images",
    val allowInsecurePublicBaseUrl: Boolean = false,
    val allowLocalStorageRemotePublicBaseUrl: Boolean = false,
    val maxInputBytes: Long = 25 * 1024 * 1024L,
    val maxPixels: Long = 100_000_000L,
    val requestConcurrency: Int = 2,
    val vipsConcurrency: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    val variantConcurrency: Int = 2,
    val processingTimeout: Duration = Duration.ofSeconds(30),
    val variants: List<ImageVariantProperties> = listOf(
        ImageVariantProperties(name = "thumb-128", maxDimension = 128, primaryThumbnail = true),
        ImageVariantProperties(name = "card-320", maxDimension = 320),
        ImageVariantProperties(name = "detail-1024", maxDimension = 1024),
    ),
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        publicBaseUrl.requireNotBlank("publicBaseUrl")
        maxInputBytes.requirePositiveNumber("maxInputBytes")
        maxPixels.requirePositiveNumber("maxPixels")
        requestConcurrency.requirePositiveNumber("requestConcurrency")
        vipsConcurrency.requirePositiveNumber("vipsConcurrency")
        variantConcurrency.requirePositiveNumber("variantConcurrency")
        processingTimeout.requireGt(Duration.ZERO, "processingTimeout")
        variants.requireNotEmpty("variants")
        variants.count { it.primaryThumbnail }.requireEquals(1, "primaryThumbnailCount")
    }
}

data class ImageVariantProperties(
    val name: String,
    val maxDimension: Int,
    val contentType: String = "image/webp",
    val extension: String = "webp",
    val primaryThumbnail: Boolean = false,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
        private val VALID_NAME = Regex("^[A-Za-z0-9._-]+$")
    }

    init {
        name.requireNotBlank("name")
        maxDimension.requirePositiveNumber("maxDimension")
        contentType.requireNotBlank("contentType")
        extension.requireNotBlank("extension")
        require(VALID_NAME.matches(name)) { "variant name must match [A-Za-z0-9._-]+: $name" }
        require(VALID_NAME.matches(extension)) { "variant extension must match [A-Za-z0-9._-]+: $extension" }
        contentType.requireEquals("image/webp", "contentType")
        extension.requireEquals("webp", "extension")
    }
}
