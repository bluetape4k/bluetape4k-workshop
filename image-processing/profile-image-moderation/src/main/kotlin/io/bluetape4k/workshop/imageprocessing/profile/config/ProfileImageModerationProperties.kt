package io.bluetape4k.workshop.imageprocessing.profile.config

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

@ConfigurationProperties(prefix = "workshop.profile-image-moderation")
/**
 * Configuration properties for the profile-image moderation workshop example.
 */
data class ProfileImageModerationProperties(
    val publicBaseUrl: String = "http://localhost:8080/public-images",
    val defaultImageUrl: String = "http://localhost:8080/public-images/profile-images/default/default-profile.jpg",
    val allowInsecurePublicBaseUrl: Boolean = false,
    val allowLocalStorageRemotePublicBaseUrl: Boolean = false,
    val maxInputBytes: Long = 10 * 1024 * 1024L,
    val maxPixels: Long = 25_000_000L,
    val maxWidth: Int = 6_000,
    val maxHeight: Int = 6_000,
    val requestConcurrency: Int = 4,
    val moderationConcurrency: Int = 2,
    val processingTimeout: Duration = Duration.ofSeconds(10),
    val moderationTimeout: Duration = Duration.ofSeconds(3),
    val decisionDelay: Duration = Duration.ofSeconds(1),
    val rejectedFilenameMarker: String = "reject",
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        publicBaseUrl.requireNotBlank("publicBaseUrl")
        defaultImageUrl.requireNotBlank("defaultImageUrl")
        maxInputBytes.requirePositiveNumber("maxInputBytes")
        maxPixels.requirePositiveNumber("maxPixels")
        maxWidth.requirePositiveNumber("maxWidth")
        maxHeight.requirePositiveNumber("maxHeight")
        requestConcurrency.requirePositiveNumber("requestConcurrency")
        moderationConcurrency.requirePositiveNumber("moderationConcurrency")
        processingTimeout.requireGt(Duration.ZERO, "processingTimeout")
        moderationTimeout.requireGt(Duration.ZERO, "moderationTimeout")
        require(!decisionDelay.isNegative) { "decisionDelay must be zero or positive" }
        rejectedFilenameMarker.requireNotBlank("rejectedFilenameMarker")
    }
}
