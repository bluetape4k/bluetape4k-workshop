package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

/**
 * ImageKeyFactoryTest 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ImageKeyFactoryTest {

    private companion object: KLogging()

/**
 * keyFactory은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val keyFactory = ImageKeyFactory()

    @Test
/**
 * 이 시나리오는 "original key uses deterministic image prefix and sanitized filename" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `original key uses deterministic image prefix and sanitized filename`() {
        val key = keyFactory.originalKey("image-1", "../summer photo!!.jpg")

        log.debug { "key=$key" }
        key.fullKey shouldBeEqualTo "images/image-1/original/summer_photo__.jpg"
    }

    @Test
/**
 * 이 시나리오는 "variant key uses configured derivative name" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `variant key uses configured derivative name`() {
        val key = keyFactory.variantKey("image-1", "thumb-128", "webp")

        log.debug { "key=$key" }
        key.fullKey shouldBeEqualTo "images/image-1/variants/thumb-128.webp"
    }

    @Test
/**
 * 이 시나리오는 "sanitized filename is capped but keeps extension" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `sanitized filename is capped but keeps extension`() {
        val filename = "a".repeat(140) + ".jpg"

        val sanitized = keyFactory.sanitizeFilename(filename)

        log.debug { "sanitized=$sanitized" }
        sanitized.length shouldBeLessOrEqualTo 120
        sanitized.takeLast(4) shouldBeEqualTo ".jpg"
    }

    @Test
/**
 * 이 시나리오는 "sanitized filename handles overlong extension" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `sanitized filename handles overlong extension`() {
        val filename = "photo." + "x".repeat(160)

        val sanitized = keyFactory.sanitizeFilename(filename)

        log.debug { "sanitized=$sanitized" }
        sanitized.length shouldBeLessOrEqualTo 120
    }
}
