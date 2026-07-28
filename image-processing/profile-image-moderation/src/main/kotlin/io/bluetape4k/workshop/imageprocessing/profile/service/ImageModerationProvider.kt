package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationDecision
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationRequest
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationResult
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.io.Serializable

/**
 * 비동기 프로필 이미지 검수 결정 계약입니다.
 */
interface ImageModerationProvider {
    suspend fun moderate(request: ModerationRequest): ModerationResult
}

/**
 * 금지 심볼과 텍스트에 대한 프로필 이미지 검수 정책 제공자입니다.
 *
 * 워크숍은 결정적 데모 감지를 포함하므로
 * 클라우드 자격 증명 없이 실행할 수 있습니다. 프로덕션 배포에서는 [detect]를 AWS
 * Rekognition 검수 라벨, 로컬 정책 심볼용 Custom Labels 모델,
 * OCR/텍스트 검수 또는 사람 검토 큐로 대체하되 정책 결정
 * 매핑과 프로필 이미지 상태 흐름은 유지해야 합니다.
 */
@Component
class ProfileImageModerationPolicyProvider(
    private val properties: ProfileImageModerationProperties,
) : ImageModerationProvider {

    override suspend fun moderate(request: ModerationRequest): ModerationResult {
        if (!properties.decisionDelay.isZero) {
            delay(properties.decisionDelay.toMillis())
        }

        val detections = detect(request)
        return detections.firstOrNull()?.let { detection ->
            ModerationResult(ModerationDecision.REJECTED, detection.reason)
        } ?: ModerationResult(ModerationDecision.APPROVED, "No banned profile-image content detected")
    }

    private fun detect(request: ModerationRequest): List<ProfileContentDetection> {
        val filename = request.originalFilename.orEmpty().lowercase()
        return buildList {
            if (filename.contains("nazi") || filename.contains(properties.rejectedFilenameMarker.lowercase())) {
                add(ProfileContentDetection("HATE_SYMBOL", "Nazi Party", "profile image contains a banned hate symbol"))
            }
            if (filename.contains("rising-sun") ||
                filename.contains("rising-sun-flag") ||
                filename.contains("imperial-flag") ||
                filename.contains("imperial-japanese-navy-flag") ||
                filename.contains("욱일기") ||
                filename.contains("旭日旗")
            ) {
                add(ProfileContentDetection("HATE_SYMBOL", "Rising Sun Flag", "profile image contains a banned hate symbol"))
            }
            if (filename.contains("hate-text") || filename.contains("hate-speech")) {
                add(ProfileContentDetection("SENSITIVE_TEXT", "Hate Expression", "profile image contains banned hate expression text"))
            }
        }
    }

    private data class ProfileContentDetection(
        val category: String,
        val rawLabel: String,
        val reason: String,
    ) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
