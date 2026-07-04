package io.bluetape4k.workshop.imageprocessing.advanced.config

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import java.time.Duration

class ImageProcessingAdvancedPropertiesTest {

    @Test
    fun `processing timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ImageProcessingAdvancedProperties(processingTimeout = Duration.ZERO)
        }
    }

    @Test
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
    fun `variant content type must match webp encoder`() {
        assertFailsWith<IllegalArgumentException> {
            ImageVariantProperties(name = "thumb-128", maxDimension = 128, contentType = "image/jpeg")
        }
    }

    @Test
    fun `variant extension must match webp encoder`() {
        assertFailsWith<IllegalArgumentException> {
            ImageVariantProperties(name = "thumb-128", maxDimension = 128, extension = "jpg")
        }
    }
}
