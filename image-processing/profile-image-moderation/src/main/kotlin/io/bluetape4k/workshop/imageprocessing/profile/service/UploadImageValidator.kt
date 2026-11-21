package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.images.spring.UploadOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import org.springframework.stereotype.Component

@Component
/**
 * 저장 전에 선언된 프로필 이미지 메타데이터와 magic byte를 검증합니다.
 * @property properties 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class UploadImageValidator(
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ProfileImageModerationProperties,
) {

    /**
     * validateDeclaredSize은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와
     * 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param sizeBytes 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    fun validateDeclaredSize(sizeBytes: Long) {
        sizeBytes.requireInRange(0L, properties.maxInputBytes, "sizeBytes")
    }

    /**
     * validate은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param contentType 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun validate(contentType: String?, bytes: ByteArray): UploadOptions {
        bytes.size.requirePositiveNumber("bytes.size")
        validateDeclaredSize(bytes.size.toLong())
        val normalized = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        require(normalized in MAGIC_BYTES) { "unsupported image contentType: ${contentType ?: "<missing>"}" }
        require(MAGIC_BYTES.getValue(normalized)(bytes)) { "uploaded image bytes do not match contentType: $normalized" }
        return UploadOptions(contentType = normalized)
    }

    companion object: KLogging() {
        /**
         * `MAGIC_BYTES`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
         *
         * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
         */
        private val MAGIC_BYTES = mapOf<String, (ByteArray) -> Boolean>(
            "image/jpeg" to { bytes -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() },
            "image/png" to { bytes ->
                bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
                        bytes[3] == 0x47.toByte() && bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
                        bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
            },
        )
    }
}
