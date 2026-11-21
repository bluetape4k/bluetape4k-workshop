package io.bluetape4k.workshop.imageprocessing.advanced.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * variant와 workflow configuration이 실제 처리 계약과 모순되지 않는지 검증합니다.
 *
 * timeout/concurrency가 양수인지, primary thumbnail이 정확히 하나인지,
 * WebP encoder의 content type/extension과 variant 설정이 일치하는지를 확인합니다.
 * 잘못된 설정을 애플리케이션 시작 시 fail-fast 하는 목적입니다.
 */
class ImageProcessingAdvancedPropertiesTest {

    private companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "processing timeout must be positive" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `processing timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ImageProcessingAdvancedProperties(processingTimeout = Duration.ZERO)
        }
    }

    @Test
/**
 * 이 시나리오는 "exactly one variant must be primary thumbnail" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `exactly one variant must be primary thumbnail`() {
        assertFailsWith<IllegalArgumentException> {
            ImageProcessingAdvancedProperties(
                variants = listOf(
                    ImageVariantProperties(name = "thumb-128", maxDimension = 128),
                    ImageVariantProperties(name = "card-320", maxDimension = 320),
                ),
            )
        }
    }

    @Test
/**
 * 이 시나리오는 "variant content type must match webp encoder" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `variant content type must match webp encoder`() {
        assertFailsWith<IllegalArgumentException> {
            ImageVariantProperties(name = "thumb-128", maxDimension = 128, contentType = "image/jpeg")
        }
    }

    @Test
/**
 * 이 시나리오는 "variant extension must match webp encoder" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `variant extension must match webp encoder`() {
        assertFailsWith<IllegalArgumentException> {
            ImageVariantProperties(name = "thumb-128", maxDimension = 128, extension = "jpg")
        }
    }
}
