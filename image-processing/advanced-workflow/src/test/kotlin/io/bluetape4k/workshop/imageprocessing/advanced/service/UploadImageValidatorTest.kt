package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

/**
 * native image 처리 전에 적용되는 입력 gate를 설명합니다.
 *
 * 허용 MIME과 magic byte 일치, GIF/미지원 형식 거부, encoded byte 제한을
 * 분리해 검증합니다. 즉 “파일명이 jpg”라는 사실만으로 안전하다고 판단하지
 * 않으며, 이 경계가 통과한 뒤에만 값비싼 decode가 시작됩니다.
 */
class UploadImageValidatorTest {

    private companion object: KLogging()

/**
 * validator은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val validator = UploadImageValidator(testProperties(maxInputBytes = 4))

    @Test
/**
 * 이 시나리오는 "validator accepts supported image content type" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `validator accepts supported image content type`() {
        val options = validator.validate("image/jpeg", SAMPLE_JPEG_BYTES)

        log.debug { "options=$options" }
        options.contentType shouldBeEqualTo "image/jpeg"
    }

    @Test
/**
 * 이 시나리오는 "validator rejects unsupported content type" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `validator rejects unsupported content type`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate("text/plain", "plain text".encodeToByteArray())
        }
    }

    @Test
/**
 * 이 시나리오는 "validator rejects content type and magic byte mismatch" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `validator rejects content type and magic byte mismatch`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate("image/jpeg", "plain text".encodeToByteArray())
        }
    }

    @Test
/**
 * 이 시나리오는 "validator rejects gif because animated inputs are out of scope" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `validator rejects gif because animated inputs are out of scope`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate("image/gif", "GIF89a".encodeToByteArray())
        }
    }

    @Test
/**
 * 이 시나리오는 "validator rejects too large input" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `validator rejects too large input`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate("image/jpeg", byteArrayOf(1, 2, 3, 4, 5))
        }
    }
}
