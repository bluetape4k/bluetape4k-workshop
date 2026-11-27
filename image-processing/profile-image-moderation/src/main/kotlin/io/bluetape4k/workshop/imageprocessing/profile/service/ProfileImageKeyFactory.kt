package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireMatches
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.imageprocessing.profile.model.ProfileImageKeys
import io.bluetape4k.images.spring.policy.SafeFilenamePolicy
import org.springframework.stereotype.Component

@Component
/**
 * 비공개 원본과 공개 파생 이미지용 안전한 객체 스토리지 키를 만듭니다.
 */
class ProfileImageKeyFactory {

    private val filenamePolicy = SafeFilenamePolicy(preserveExtension = false)

    private companion object: KLogging() {
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val USER_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,80}$")
    }

    /**
     * validateUserId은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분
     * 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param userId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun validateUserId(userId: String): String {
        userId.requireNotBlank("userId")
        userId.requireMatches(USER_ID_REGEX, "userId") { "userId must match [A-Za-z0-9._-]{1,80}" }
        return userId
    }

    /**
     * keys은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param userId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param uploadId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param filename 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    fun keys(userId: String, uploadId: String, filename: String?): ProfileImageKeys {
        val safeUserId = validateUserId(userId)
        uploadId.requireNotBlank("uploadId")
        return ProfileImageKeys(
            original = ImageObjectKey(
                "profile-images/$safeUserId/$uploadId/private/original",
                sanitizeFilename(filename)
            ),
            pending = ImageObjectKey("profile-images/$safeUserId/$uploadId/pending", "blurred.jpg"),
            approved = ImageObjectKey("profile-images/$safeUserId/$uploadId/public", "approved.jpg"),
        )
    }

    /**
     * sanitizeFilename은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param filename 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    fun sanitizeFilename(filename: String?): String = filenamePolicy.sanitize(filename)
}
