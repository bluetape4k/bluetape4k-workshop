package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationDecision
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationRequest
import io.bluetape4k.workshop.imageprocessing.profile.model.ModerationResult
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.io.Serializable
import kotlin.time.toKotlinDuration

/**
 * 비동기 프로필 이미지 검수 결정 계약입니다.
 */
interface ImageModerationProvider {
    /**
     * moderate은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 이미지 업로드·검수·파생물 처리 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
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
 * @property properties 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
class ProfileImageModerationPolicyProvider(
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ProfileImageModerationProperties,
): ImageModerationProvider {

    private companion object: KLogging()

    /**
     * moderate은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 이미지 업로드·검수·파생물 처리 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    override suspend fun moderate(request: ModerationRequest): ModerationResult {
        if (!properties.decisionDelay.isZero) {
            delay(properties.decisionDelay.toKotlinDuration())
        }

        val detections = detect(request)
        return detections.firstOrNull()
            ?.let { detection ->
                ModerationResult(ModerationDecision.REJECTED, detection.reason)
            } ?: ModerationResult(ModerationDecision.APPROVED, "No banned profile-image content detected")
    }

    /**
     * detect은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 이미지 업로드·검수·파생물 처리 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
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
                add(
                    ProfileContentDetection(
                        "HATE_SYMBOL",
                        "Rising Sun Flag",
                        "profile image contains a banned hate symbol"
                    )
                )
            }
            if (filename.contains("hate-text") || filename.contains("hate-speech")) {
                add(
                    ProfileContentDetection(
                        "SENSITIVE_TEXT",
                        "Hate Expression",
                        "profile image contains banned hate expression text"
                    )
                )
            }
        }
    }
/**
 * ProfileContentDetection은 이미지 업로드·검사·파생물 처리에서 전달되는 불변 데이터 모델입니다.
 * @property category 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property rawLabel 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property reason 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 */


    private data class ProfileContentDetection(
        val category: String,
        val rawLabel: String,
        val reason: String,
    ): Serializable {
        companion object {
            /**
             * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
             *
             * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
             */
            private const val serialVersionUID: Long = 1L
        }
    }
}
