package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class ProfileImageProcessorTest {

    @Test
    fun generated_derivatives_are_jpeg_bytes() {
        val fixture = ProfileImageServiceFixture()
        val processed = ProfileImageProcessor(fixture.properties).process(fixture.sampleJpeg())

        processed.contentType shouldBeEqualTo "image/jpeg"
        processed.pendingBytes.isJpeg() shouldBeEqualTo true
        processed.approvedBytes.isJpeg() shouldBeEqualTo true
    }

    private fun ByteArray.isJpeg(): Boolean =
        size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()
}
