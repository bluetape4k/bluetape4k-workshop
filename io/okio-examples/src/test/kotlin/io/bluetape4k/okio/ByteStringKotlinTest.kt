package io.bluetape4k.okio

import io.bluetape4k.io.okio.byteStringOf
import io.bluetape4k.logging.KLogging
import okio.ByteString.Companion.encode
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.readByteString
import okio.ByteString.Companion.toByteString
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class ByteStringKotlinTest: AbstractOkioTest() {

    companion object: KLogging()

    @Test
    fun `array to byte string`() {
        val actual = byteArrayOf(1, 2, 3, 4).toByteString()
        val expected = byteStringOf(1, 2, 3, 4)
        actual shouldBeEqualTo expected
    }

    @Test
    fun `byte buffer to byte string`() {
        val actual = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)).toByteString()
        val expected = byteStringOf(1, 2, 3, 4)

        actual shouldBeEqualTo expected
    }

    @Test
    fun `string encode byte string with default charset`() {
        val actual = "a\uD83C\uDF69c".encode()
        val expected = "a\uD83C\uDF69c".encodeUtf8()

        actual shouldBeEqualTo expected
    }

    @Test
    fun `read byte string from stream`() {
        val stream = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        stream.readByteString(4) shouldBeEqualTo byteStringOf(1, 2, 3, 4)
        stream.readByteString(stream.available()) shouldBeEqualTo byteStringOf(5, 6, 7, 8)
    }

    @Test
    fun `substring with byte string`() {
        val byteString = "abcdef".encodeUtf8()

        byteString.substring() shouldBeEqualTo "abcdef".encodeUtf8()
        byteString.substring(endIndex = 3) shouldBeEqualTo "abc".encodeUtf8()    // 0 <= x < 3
        byteString.substring(beginIndex = 3) shouldBeEqualTo "def".encodeUtf8()  // 3 <= x < length
        byteString.substring(beginIndex = 1, endIndex = 5) shouldBeEqualTo "bcde".encodeUtf8()  // 1 <= x < 5
    }
}
