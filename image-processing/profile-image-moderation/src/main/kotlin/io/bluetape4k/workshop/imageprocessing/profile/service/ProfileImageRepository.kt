package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationDecision
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationResult
import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageState
import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageStatus
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Repository
/**
 * 워크숍 예제용 인메모리 프로필 이미지 상태 저장소입니다.
 */
class ProfileImageRepository {

    private companion object: KLogging()

    private val states = ConcurrentHashMap<String, ProfileImageState>()

    fun savePending(state: ProfileImageState): ProfileImageState {
        states[state.userId] = state
        return state
    }

    fun find(userId: String): ProfileImageState? = states[userId]

    fun completeModeration(userId: String, uploadId: String, result: ModerationResult): Boolean {
        var completed = false
        states.computeIfPresent(userId) { _, current ->
            if (current.uploadId != uploadId || current.status != ProfileImageStatus.PENDING_MODERATION) {
                current
            } else {
                completed = true
                val status = when (result.decision) {
                    ModerationDecision.APPROVED -> ProfileImageStatus.APPROVED
                    ModerationDecision.REJECTED -> ProfileImageStatus.REJECTED
                }
                current.copy(
                    status = status,
                    reason = result.reason,
                    updatedAt = Instant.now()
                )
            }
        }
        return completed
    }

    fun failModeration(userId: String, uploadId: String, reason: String): Boolean {
        var completed = false
        states.computeIfPresent(userId) { _, current ->
            if (current.uploadId != uploadId || current.status != ProfileImageStatus.PENDING_MODERATION) {
                current
            } else {
                completed = true
                current.copy(
                    status = ProfileImageStatus.MODERATION_FAILED,
                    reason = reason,
                    updatedAt = Instant.now()
                )
            }
        }
        return completed
    }
}
