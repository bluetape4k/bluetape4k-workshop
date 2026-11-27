package io.bluetape4k.workshop.shared.image

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class SafeFilenamePolicyTest {

    @Test
    fun `filename keeps only the final segment and safe characters`() {
        SafeFilenamePolicy().sanitize("C:\\tmp\\holiday photo.jpg") shouldBeEqualTo "holiday_photo.jpg"
        SafeFilenamePolicy().sanitize("../") shouldBeEqualTo "upload.jpg"
    }

    @Test
    fun `long filename can preserve its extension`() {
        val policy = SafeFilenamePolicy(maxLength = 12, preserveExtension = true)

        policy.sanitize("very-long-name.webp") shouldBeEqualTo "very-lo.webp"
    }

    @Test
    fun `overlong extension keeps the original filename prefix`() {
        val policy = SafeFilenamePolicy(maxLength = 12, preserveExtension = true)

        policy.sanitize("photo." + "x".repeat(20)) shouldBeEqualTo "photo.xxxxxx"
    }

    @Test
    fun `long filename can preserve the profile truncation contract`() {
        val policy = SafeFilenamePolicy(maxLength = 12, preserveExtension = false)

        policy.sanitize("very-long-name.webp") shouldBeEqualTo "very-long-na"
    }
}
