package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class FfmVipsDerivativeProcessorTest : AbstractFfmVipsWorkshopTest() {

    @Test
    fun `processor generates webp variants with bounded dimensions`() = runSuspendIO {
        val processor = FfmVipsDerivativeProcessor(
            properties = testProperties(maxInputBytes = 1024 * 1024),
            keyFactory = ImageKeyFactory(),
        )

        val processed = processor.process(sampleJpegBytes(), "image-1")

        processed.original.width shouldBeEqualTo 640
        processed.original.height shouldBeEqualTo 480
        processed.variants.map { it.name } shouldBeEqualTo listOf("thumb-128", "card-320", "detail-1024")
        processed.variants.forEach { variant ->
            maxOf(variant.width, variant.height) shouldBeLessOrEqualTo variant.name.substringAfter('-').toInt()
            variant.contentType shouldBeEqualTo "image/webp"
            variant.key.fullKey shouldBeEqualTo "images/image-1/variants/${variant.name}.webp"
        }
    }

    private fun sampleJpegBytes(): ByteArray {
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0x2F, 0x80, 0xED)
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.dispose()
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "jpg", output)
            output.toByteArray()
        }
    }
}
