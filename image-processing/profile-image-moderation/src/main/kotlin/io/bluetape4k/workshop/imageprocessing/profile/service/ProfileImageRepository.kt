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
    /**
     * 이미지 처리의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    private val states = ConcurrentHashMap<String, ProfileImageState>()

    /**
     * savePending은 이미지 업로드·검수·파생물 처리 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
     * 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun savePending(state: ProfileImageState): ProfileImageState {
        states[state.userId] = state
        return state
    }

    /**
     * find은 이미지 업로드·검수·파생물 처리의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재 상태를 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param userId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun find(userId: String): ProfileImageState? = states[userId]

    /**
     * completeModeration은 이미지 업로드·검수·파생물 처리의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한
     * 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param userId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param uploadId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
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

    /**
     * failModeration은 이미지 업로드·검수·파생물 처리 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사
     * 필드를 함께
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param userId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param uploadId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
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
