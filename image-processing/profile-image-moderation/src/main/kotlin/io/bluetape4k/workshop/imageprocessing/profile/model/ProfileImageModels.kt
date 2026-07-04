package io.bluetape4k.workshop.imageprocessing.profile.model

import io.bluetape4k.images.spring.ImageObjectKey
import java.io.Serializable
import java.time.Instant

/**
 * Lifecycle state for the effective profile-image URL returned by the example API.
 */
enum class ProfileImageStatus {
    NO_IMAGE,
    PENDING_MODERATION,
    APPROVED,
    REJECTED,
    MODERATION_FAILED,
}

/**
 * Minimal moderation verdict used by the local fake provider and service workflow.
 */
enum class ModerationDecision {
    APPROVED,
    REJECTED,
}

/**
 * API-facing profile-image projection with the currently effective URL.
 */
data class ProfileImageView(
    val userId: String,
    val status: ProfileImageStatus,
    val uploadId: String?,
    val effectiveUrl: String,
    val pendingUrl: String?,
    val approvedUrl: String?,
    val defaultImageUrl: String,
    val reason: String?,
    val updatedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Object-storage keys produced for one profile-image upload.
 */
data class ProfileImageKeys(
    val original: ImageObjectKey,
    val pending: ImageObjectKey,
    val approved: ImageObjectKey,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * JPEG derivatives produced from the uploaded image before moderation starts.
 */
data class ProcessedProfileImage(
    val width: Int,
    val height: Int,
    val pendingBytes: ByteArray,
    val approvedBytes: ByteArray,
    val contentType: String = "image/jpeg",
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Mutable profile-image state persisted by the in-memory example repository.
 */
data class ProfileImageState(
    val userId: String,
    val uploadId: String,
    val status: ProfileImageStatus,
    val keys: ProfileImageKeys,
    val pendingUrl: String,
    val approvedUrl: String,
    val defaultImageUrl: String,
    val reason: String? = null,
    val originalFilename: String? = null,
    val updatedAt: Instant = Instant.now(),
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    fun toView(): ProfileImageView {
        val effectiveUrl = when (status) {
            ProfileImageStatus.PENDING_MODERATION -> pendingUrl
            ProfileImageStatus.APPROVED -> approvedUrl
            ProfileImageStatus.REJECTED,
            ProfileImageStatus.MODERATION_FAILED,
            ProfileImageStatus.NO_IMAGE -> defaultImageUrl
        }
        return ProfileImageView(
            userId = userId,
            status = status,
            uploadId = uploadId,
            effectiveUrl = effectiveUrl,
            pendingUrl = pendingUrl.takeIf { status == ProfileImageStatus.PENDING_MODERATION },
            approvedUrl = approvedUrl.takeIf { status == ProfileImageStatus.APPROVED },
            defaultImageUrl = defaultImageUrl,
            reason = reason,
            updatedAt = updatedAt,
        )
    }
}

/**
 * Work item handed to the asynchronous moderation runner.
 */
data class ModerationRequest(
    val userId: String,
    val uploadId: String,
    val originalFilename: String?,
    val originalKey: ImageObjectKey,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Result returned by an image moderation provider.
 */
data class ModerationResult(
    val decision: ModerationDecision,
    val reason: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
