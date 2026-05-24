package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import org.junit.jupiter.api.Test

class ImageKeyFactoryTest {

    private val keyFactory = ImageKeyFactory()

    @Test
    fun `original key uses deterministic image prefix and sanitized filename`() {
        val key = keyFactory.originalKey("image-1", "../summer photo!!.jpg")

        key.fullKey shouldBeEqualTo "images/image-1/original/summer_photo__.jpg"
    }

    @Test
    fun `variant key uses configured derivative name`() {
        val key = keyFactory.variantKey("image-1", "thumb-128", "webp")

        key.fullKey shouldBeEqualTo "images/image-1/variants/thumb-128.webp"
    }

    @Test
    fun `sanitized filename is capped but keeps extension`() {
        val filename = "a".repeat(140) + ".jpg"

        val sanitized = keyFactory.sanitizeFilename(filename)

        sanitized.length shouldBeLessOrEqualTo 120
        sanitized.takeLast(4) shouldBeEqualTo ".jpg"
    }

    @Test
    fun `sanitized filename handles overlong extension`() {
        val filename = "photo." + "x".repeat(160)

        val sanitized = keyFactory.sanitizeFilename(filename)

        sanitized.length shouldBeLessOrEqualTo 120
    }
}
