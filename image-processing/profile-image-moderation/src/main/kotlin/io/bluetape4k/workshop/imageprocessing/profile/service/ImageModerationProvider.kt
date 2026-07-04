package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationDecision
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationRequest
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationResult
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.io.Serializable

/**
 * Contract for asynchronous profile-image moderation decisions.
 */
interface ImageModerationProvider {
    suspend fun moderate(request: ModerationRequest): ModerationResult
}

/**
 * Profile-image moderation policy provider for banned symbols and text.
 *
 * The workshop ships with deterministic demo detections so it can run without
 * cloud credentials. Production deployments should replace [detect] with AWS
 * Rekognition moderation labels, a Custom Labels model for local policy symbols,
 * OCR/text moderation, or a human-review queue while keeping the policy decision
 * mapping and profile-image state flow intact.
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
            if (filename.contains("rising-sun") || filename.contains("imperial-flag")) {
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
