package io.bluetape4k.workshop.imageprocessing.advanced.config

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test

class ImageProcessingAdvancedPropertiesTest {

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
