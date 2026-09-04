package io.bluetape4k.workshop.imageprocessing.profile.model

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.privacy.PrivacyDerivativeReport
import java.io.Serializable
import java.time.Instant
import kotlin.jvm.Transient

/**
 * 예제 API가 반환하는 유효 프로필 이미지 URL의 생명주기 상태입니다.
 */
enum class ProfileImageStatus {
    NO_IMAGE,
    PENDING_MODERATION,
    APPROVED,
    REJECTED,
    MODERATION_FAILED,
}

/**
 * 로컬 모의 제공자와 서비스 처리 흐름이 사용하는 최소 검수 판정입니다.
 */
enum class ModerationDecision {
    APPROVED,
    REJECTED,
}

/**
 * 현재 유효 URL을 포함한 API 대상 프로필 이미지 표현입니다.
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
 * 프로필 이미지 업로드 하나에서 생성되는 객체 스토리지 키입니다.
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
 * 검수 시작 전에 업로드 이미지에서 생성되는 JPEG 파생 이미지입니다.
 */
data class ProcessedProfileImage(
    val width: Int,
    val height: Int,
    val pendingBytes: ByteArray,
    val approvedBytes: ByteArray,
    val contentType: String = "image/jpeg",
    @Transient val pendingPrivacyReport: PrivacyDerivativeReport? = null,
    @Transient val approvedPrivacyReport: PrivacyDerivativeReport? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 인메모리 예제 저장소가 영속화하는 변경 가능한 프로필 이미지 상태입니다.
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
 * 비동기 검수 실행기에 전달되는 작업 항목입니다.
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
 * 이미지 검수 제공자가 반환하는 결과입니다.
 */
data class ModerationResult(
    val decision: ModerationDecision,
    val reason: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
