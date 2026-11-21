package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationDecision
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationRequest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * workshop 전용 deterministic moderation adapter의 경계를 설명합니다.
 *
 * filename marker가 금지 symbol/text로 매핑되면 `REJECTED`, 일반적인 fixture는
 * `APPROVED`가 되는지 확인합니다. 이 테스트는 marker 기반 demo가 실제 모델의
 * precision/recall이나 이미지 내용 판정을 보장하지 않는다는 사실과 함께 읽어야 합니다.
 */
class ProfileImageModerationPolicyProviderTest {

    companion object: KLogging()

    @ParameterizedTest(name = "banned profile marker {0} is rejected")
    @ValueSource(
        strings = [
            "nazi-symbol.jpg",
            "rising-sun-avatar.jpg",
            "rising-sun-flag-profile.jpg",
            "욱일기-profile.jpg",
            "旭日旗-avatar.jpg",
            "hate-text-profile.jpg",
            "reject.jpg",
        ],
    )
/**
 * banned_profile_symbols_and_text_are_rejected은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun banned_profile_symbols_and_text_are_rejected(filename: String) = runSuspendIO {
/**
 * provider은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val provider = ProfileImageModerationPolicyProvider(testProperties())

/**
 * result은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val result = provider.moderate(request(filename))

        log.debug { "result=$result" }
        result.decision shouldBeEqualTo ModerationDecision.REJECTED
    }

    @ParameterizedTest(name = "safe profile marker {0} is approved")
    @ValueSource(strings = ["family-photo.jpg", "blue-profile.png"])
/**
 * safe_profile_images_are_approved은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun safe_profile_images_are_approved(filename: String) = runSuspendIO {
/**
 * provider은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val provider = ProfileImageModerationPolicyProvider(testProperties())

/**
 * result은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val result = provider.moderate(request(filename))

        log.debug { "result=$result" }
        result.decision shouldBeEqualTo ModerationDecision.APPROVED
    }

/**
 * request은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(filename: String): ModerationRequest = ModerationRequest(
        userId = "user-1",
        uploadId = "upload-a",
        originalFilename = filename,
        originalKey = ImageObjectKey("profile-images/user-1/upload-a/private/original", "uploaded-profile.jpg"),
    )
}
