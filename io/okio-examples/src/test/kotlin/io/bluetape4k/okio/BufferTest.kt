package io.bluetape4k.okio

import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.logging.KLogging
import okio.Buffer
import okio.IOException
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.*
import kotlin.test.assertFailsWith

class BufferTest: AbstractOkioTest() {

    companion object: KLogging()

    @Test
    fun `copy to spanning segments`() {
        val source = Buffer()
        source.writeUtf8("a".repeat(SEGMENT_SIZE * 2))
        source.writeUtf8("b".repeat(SEGMENT_SIZE * 2))

        val out = ByteArrayOutputStream()
        source.copyTo(out, 10L, SEGMENT_SIZE * 3L)

        out.toString() shouldBeEqualTo
                "a".repeat(SEGMENT_SIZE * 2 - 10) + "b".repeat(SEGMENT_SIZE + 10)

        source.readUtf8(SEGMENT_SIZE * 4L) shouldBeEqualTo
                "a".repeat(SEGMENT_SIZE * 2) + "b".repeat(SEGMENT_SIZE * 2)
    }

    @Test
    fun `copy to skipping segments`() {
        val source = Buffer()
        source.writeUtf8("a".repeat(SEGMENT_SIZE * 2))
        source.writeUtf8("b".repeat(SEGMENT_SIZE * 2))

        val out = ByteArrayOutputStream()
        source.copyTo(out, SEGMENT_SIZE * 2 + 1L, 3L)

        out.toString() shouldBeEqualTo "bbb"

        source.readUtf8(SEGMENT_SIZE * 4L) shouldBeEqualTo
                "a".repeat(SEGMENT_SIZE * 2) + "b".repeat(SEGMENT_SIZE * 2)
    }

    @Test
    fun `copy to stream`() {
        val expected = faker.lorem().paragraph()
        val buffer = bufferOf(expected)
        val out = ByteArrayOutputStream()
        buffer.copyTo(out)
        val outString = out.toByteArray().toString(Charsets.UTF_8)
        outString shouldBeEqualTo expected
        buffer.readUtf8() shouldBeEqualTo expected
    }

    @Test
    fun `write to spanning segments`() {
        val buffer = Buffer()
        buffer.writeUtf8("a".repeat(SEGMENT_SIZE * 2))
        buffer.writeUtf8("b".repeat(SEGMENT_SIZE * 2))

        val out = ByteArrayOutputStream()
        buffer.skip(10L)
        buffer.writeTo(out, SEGMENT_SIZE * 3L)

        out.toString() shouldBeEqualTo "a".repeat(SEGMENT_SIZE * 2 - 10) + "b".repeat(SEGMENT_SIZE + 10)
        buffer.readUtf8(buffer.size) shouldBeEqualTo "b".repeat(SEGMENT_SIZE - 10)
    }

    @Test
    fun `write to stream`() {
        val expected = faker.lorem().paragraph()
        val buffer = bufferOf(expected)
        val out = ByteArrayOutputStream()
        buffer.writeTo(out)
        val outString = out.toByteArray().toString(Charsets.UTF_8)
        outString shouldBeEqualTo expected
        buffer.size shouldBeEqualTo 0L
    }

    @Test
    fun `read from stream`() {
        val expected = faker.lorem().paragraph()
        val input = ByteArrayInputStream(expected.toByteArray(Charsets.UTF_8))
        val buffer = Buffer()
        buffer.readFrom(input)
        val out = buffer.readUtf8()
        out shouldBeEqualTo expected
    }

    @Test
    fun `read from spanning segments`() {
        val expected = faker.lorem().paragraph()
        val input = ByteArrayInputStream(expected.toByteArray(Charsets.UTF_8))
        val buffer = Buffer().writeUtf8("a".repeat(SEGMENT_SIZE - 10))
        buffer.readFrom(input)
        val out = buffer.readUtf8()
        out shouldBeEqualTo "a".repeat(SEGMENT_SIZE - 10) + expected
    }

    @Test
    fun `read from stream with count`() {
        val expected = "hello, world!"
        val input = ByteArrayInputStream(expected.toByteArray(Charsets.UTF_8))
        val buffer = Buffer()
        buffer.readFrom(input, 10L)  // Multi bytes 에 해당하는 한글의 경우에는 실패한다.
        val out = buffer.readUtf8()
        out shouldBeEqualTo expected.take(10)
    }

    @Test
    fun `read from stream throws EOF on exhaustion`() {
        val input = ByteArrayInputStream("hello, world!".toByteArray(Charsets.UTF_8))
        val buffer = Buffer()
        assertFailsWith<IOException> {
            buffer.readFrom(input, input.available() + 1L)
        }
    }

    @Test
    fun `read from does not leave empty tail segment`() {
        val buffer = Buffer()
        buffer.readFrom(ByteArrayInputStream(ByteArray(SEGMENT_SIZE)))
        buffer.completeSegmentByteCount() shouldBeEqualTo SEGMENT_SIZE.toLong()
    }

    @Test
    fun `buffer input stream byte by byte`() {
        val source = Buffer()
        source.writeUtf8("abc")
        val input = source.inputStream()
        input.available() shouldBeEqualTo 3
        input.read() shouldBeEqualTo 'a'.code
        input.read() shouldBeEqualTo 'b'.code
        input.read() shouldBeEqualTo 'c'.code
        input.read() shouldBeEqualTo -1 // End of stream
        input.available() shouldBeEqualTo 0
    }

    @Test
    fun `buffer input stream bulk reads`() {
        val source = Buffer()
        source.writeUtf8("abc")
        val byteArray = ByteArray(4)
        Arrays.fill(byteArray, (-5).toByte())

        val input = source.inputStream()
        input.read(byteArray) shouldBeEqualTo 3
        byteArray.contentToString() shouldBeEqualTo "[97, 98, 99, -5]"

        Arrays.fill(byteArray, (-7).toByte())
        input.read(byteArray) shouldBeEqualTo -1 // End of stream
        byteArray.contentToString() shouldBeEqualTo "[-7, -7, -7, -7]"
    }

    @Test
    fun `copy to output stream`() {
        val source = bufferOf("party")

        val target = Buffer()
        source.copyTo(target.outputStream())
        target.readUtf8() shouldBeEqualTo "party"
        source.readUtf8() shouldBeEqualTo "party"
    }

    @Test
    fun `copy to output stream with offset and length`() {
        val source = bufferOf("party")

        val target = Buffer()
        source.copyTo(target.outputStream(), offset = 2L, source.size - 2L)
        target.readUtf8() shouldBeEqualTo "rty"
        source.readUtf8() shouldBeEqualTo "party"
    }

    @Test
    fun `copy to output stream with offset and length 2`() {
        val source = bufferOf("party")

        val target = Buffer()
        source.copyTo(target.outputStream(), offset = 1L, source.size - 2L)
        target.readUtf8() shouldBeEqualTo "art"
        source.readUtf8() shouldBeEqualTo "party"
    }

    @Test
    fun `copy to output stream with length`() {
        val source = bufferOf("party")

        val target = Buffer()
        source.copyTo(target.outputStream(), byteCount = 3L)
        target.readUtf8() shouldBeEqualTo "par"
        source.readUtf8() shouldBeEqualTo "party"
    }

    @Test
    fun `copy to output stream with empty range`() {
        val source = bufferOf("hello")

        val target = Buffer()
        source.copyTo(target.outputStream(), offset = 1L, 0L)
        target.readUtf8() shouldBeEqualTo ""
        source.readUtf8() shouldBeEqualTo "hello"
    }

    @Test
    fun `read to output stream`() {
        val source = bufferOf("party")

        val target = Buffer()
        source.writeTo(target.outputStream())

        target.readUtf8() shouldBeEqualTo "party"
        source.readUtf8() shouldBeEqualTo ""
    }

    @Test
    fun `read to output stream with byte count`() {
        val source = bufferOf("party")

        val target = Buffer()
        source.writeTo(target.outputStream(), byteCount = 3L)

        target.readUtf8() shouldBeEqualTo "par"
        source.readUtf8() shouldBeEqualTo "ty"
    }

    @Test
    fun `read empty buffer to byte buffer`() {
        val bb = ByteBuffer.allocate(128)
        val buffer = Buffer()

        buffer.read(bb) shouldBeEqualTo -1 // No data to read
    }
}
