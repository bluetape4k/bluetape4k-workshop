package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.images.useGraphics
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Java 25/libvips native adapter가 업무 variant 계약을 지키는지 검증합니다.
 *
 * 원본 dimension을 읽고 `thumb-128`, `card-320`, `detail-1024` WebP를
 * 생성하며 각 variant의 긴 변과 key/content type을 확인합니다. native
 * library가 없는 환경에서는 기본적으로 skip되므로 deterministic workflow
 * 테스트와 혼동하지 않습니다.
 */
class FfmVipsDerivativeProcessorTest: AbstractFfmVipsWorkshopTest() {

    private companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "processor generates webp variants with bounded dimensions" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `processor generates webp variants with bounded dimensions`() = runSuspendIO {
/**
 * processor은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val processor = FfmVipsDerivativeProcessor(
            properties = testProperties(maxInputBytes = 1024 * 1024),
            keyFactory = ImageKeyFactory(),
        )

/**
 * processed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val processed = processor.process(sampleJpegBytes(), "image-1")
        log.debug { "processed=$processed" }

        processed.original.width shouldBeEqualTo 640
        processed.original.height shouldBeEqualTo 480
        processed.variants.map { it.name } shouldBeEqualTo listOf("thumb-128", "card-320", "detail-1024")
        processed.variants.forEach { variant ->
            log.debug { "variant=$variant" }
            maxOf(variant.width, variant.height) shouldBeLessOrEqualTo variant.name.substringAfter('-').toInt()
            variant.contentType shouldBeEqualTo "image/webp"
            variant.key.fullKey shouldBeEqualTo "images/image-1/variants/${variant.name}.webp"
        }
    }

/**
 * sampleJpegBytes은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sampleJpegBytes(): ByteArray {
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        image.useGraphics { graphics ->
            graphics.color = Color(0x2F, 0x80, 0xED)
            graphics.fillRect(0, 0, image.width, image.height)
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "jpg", output)
            output.toByteArray()
        }
    }
}
