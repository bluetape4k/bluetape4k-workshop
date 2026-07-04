package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationDecision
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationRequest
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationResult
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component

/**
 * Contract for asynchronous profile-image moderation decisions.
 */
interface ImageModerationProvider {
    suspend fun moderate(request: ModerationRequest): ModerationResult
}

/**
 * Deterministic local moderator used by the workshop scenario.
 */
@Component
class FakeImageModerationProvider(
    private val properties: ProfileImageModerationProperties,
) : ImageModerationProvider {

    override suspend fun moderate(request: ModerationRequest): ModerationResult {
        if (!properties.decisionDelay.isZero) {
            delay(properties.decisionDelay.toMillis())
        }
        val filename = request.originalFilename.orEmpty().lowercase()
        return if (filename.contains(properties.rejectedFilenameMarker.lowercase())) {
            ModerationResult(ModerationDecision.REJECTED, "demo filename marker matched")
        } else {
            ModerationResult(ModerationDecision.APPROVED, "demo moderation approved")
        }
    }
}
