package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class UploadImageValidatorTest {

    private val validator = UploadImageValidator(testProperties(maxInputBytes = 4))

    @Test
    fun `validator accepts supported image content type`() {
        val options = validator.validate("image/jpeg", SAMPLE_JPEG_BYTES)

        options.contentType shouldBeEqualTo "image/jpeg"
    }

    @Test
    fun `validator rejects unsupported content type`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate("text/plain", "plain text".encodeToByteArray())
        }
    }

    @Test
    fun `validator rejects content type and magic byte mismatch`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate("image/jpeg", "plain text".encodeToByteArray())
        }
    }

    @Test
    fun `validator rejects gif because animated inputs are out of scope`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate("image/gif", "GIF89a".encodeToByteArray())
        }
    }

    @Test
    fun `validator rejects too large input`() {
        assertFailsWith<IllegalArgumentException> {
            validator.validate("image/jpeg", byteArrayOf(1, 2, 3, 4, 5))
        }
    }
}
