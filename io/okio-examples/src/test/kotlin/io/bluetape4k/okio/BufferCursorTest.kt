package io.bluetape4k.okio

import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.asByte
import okio.Buffer
import okio.ByteString
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*
import kotlin.test.assertFailsWith

class BufferCursorTest: AbstractOkioTest() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5

        private val _factories = BufferFactory.factories.toList()
        private val _buffers = _factories.map { it.newBuffer() }
    }

    fun factories() = _factories
    fun buffers() = _buffers

    // TODO: LZ4, ZSTD 에서 실제 압축한 길이만큼만 resizeBuffer() 를 이용해서 사용할 수 있다.
    @Test
    fun `api example`() {
        val buffer = Buffer()
        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.resizeBuffer(1_000_000L)
            val x = 'x'.asByte()

            // NOTE: Buffer 간의 Read/Write는 while 문을 사용해야 하고, Arrays.fill() 같은 것은 do while 문을 사용해야 한다.
            do {
                Arrays.fill(cursor.data!!, cursor.start, cursor.end, x)
            } while (cursor.next() != -1)

            cursor.seek(3)
            cursor.data!![cursor.start] = 'o'.asByte()
            cursor.seek(1)
            cursor.data!![cursor.start] = 'o'.asByte()
            cursor.resizeBuffer(4)
        }
        log.debug { "buffer=$buffer" }
        buffer shouldBeEqualTo bufferOf("xoxo")
    }

    // TODO: LZ4, ZSTD 에서 실제 압축한 길이만큼만 resizeBuffer() 를 이용해서 사용할 수 있다.
    @Test
    fun `api example by kotlin function`() {
        val buffer = Buffer()
        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.resizeBuffer(1_000_000)
            val x = 'x'.asByte()

            // NOTE: Buffer 간의 Read/Write는 while 문을 사용해야 하고, Arrays.fill() 같은 것은 do while 문을 사용해야 한다.
            do {
                cursor.data!!.fill(x, cursor.start, cursor.end)
            } while (cursor.next() != -1)

            cursor.seek(3)
            cursor.data!![cursor.start] = 'o'.asByte()
            cursor.seek(1)
            cursor.data!![cursor.start] = 'o'.asByte()
            cursor.resizeBuffer(4)
        }
        log.debug { "buffer=$buffer" }
        buffer shouldBeEqualTo bufferOf("xoxo")
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `access segment by segment`(buffer: Buffer) {
        val actual = Buffer()
        buffer.readUnsafe().use { cursor ->
            // NOTE: Buffer 간의 Read/Write는 while 문을 사용해야 하고, Arrays.fill() 같은 것은 do while 문을 사용해야 한다.
            while (cursor.next() != -1) {
                actual.write(cursor.data!!, cursor.start, cursor.end - cursor.start)
            }
        }
        actual shouldBeEqualTo buffer
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `seek to negative one seeks before first segment`(buffer: Buffer) {
        buffer.readUnsafe().use { cursor ->
            cursor.seek(-1L)
            cursor.offset shouldBeEqualTo -1L
            cursor.data.shouldBeNull()

            cursor.start shouldBeEqualTo -1
            cursor.end shouldBeEqualTo -1
            cursor.next()
            cursor.offset shouldBeEqualTo 0L
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `access byte by byte`(buffer: Buffer) {
        buffer.readUnsafe().use { cursor ->
            val actual = ByteArray(buffer.size.toInt())
            repeat(buffer.size.toInt()) {
                cursor.seek(it.toLong())
                actual[it] = cursor.data!![cursor.start]
            }
            buffer.snapshot() shouldBeEqualTo ByteString.of(*actual)
        }
    }


    @ParameterizedTest
    @MethodSource("buffers")
    fun `access byte by byte reverse`(buffer: Buffer) {
        buffer.readUnsafe().use { cursor ->
            val actual = ByteArray(buffer.size.toInt())
            for (i in (buffer.size - 1).toInt() downTo 0) {
                cursor.seek(i.toLong())
                actual[i] = cursor.data!![cursor.start]
            }
            buffer.snapshot() shouldBeEqualTo ByteString.of(*actual)
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `access byte by byte always resetting to zero`(buffer: Buffer) {
        buffer.readUnsafe().use { cursor ->
            val actual = ByteArray(buffer.size.toInt())
            repeat(buffer.size.toInt()) {
                cursor.seek(it.toLong())
                actual[it] = cursor.data!![cursor.start]
                cursor.seek(0L)
            }
            buffer.snapshot() shouldBeEqualTo ByteString.of(*actual)
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `seek within segment`(buffer: Buffer) {
        Assumptions.assumeTrue { buffer == BufferFactory.SMALL_SEGMENTED_BUFFER.newBuffer() }
        buffer.clone().readUtf8() shouldBeEqualTo "abcdefghijkl"

        buffer.readUnsafe().use { cursor ->
            cursor.seek(5).toLong() shouldBeEqualTo 2 // 2 for 2 bytes left in the segment: "fg"
            cursor.offset shouldBeEqualTo 5L
            (cursor.end - cursor.start).toLong() shouldBeEqualTo 2L
            cursor.data!![cursor.start - 2].toInt().toChar().code shouldBeEqualTo 'd'.code
            cursor.data!![cursor.start - 1].toInt().toChar().code shouldBeEqualTo 'e'.code
            cursor.data!![cursor.start].toInt().toChar().code shouldBeEqualTo 'f'.code
            cursor.data!![cursor.start + 1].toInt().toChar().code shouldBeEqualTo 'g'.code
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `acquire and release`(buffer: Buffer) {
        val cursor = Buffer.UnsafeCursor()

        // Nothing initialized before acquire.
        cursor.offset shouldBeEqualTo -1L
        cursor.data.shouldBeNull()
        cursor.start shouldBeEqualTo -1
        cursor.end shouldBeEqualTo -1

        buffer.readUnsafe(cursor)
        cursor.close()

        // Nothing initialized after close.
        cursor.offset shouldBeEqualTo -1L
        cursor.data.shouldBeNull()
        cursor.start shouldBeEqualTo -1
        cursor.end shouldBeEqualTo -1
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `double acquire`(buffer: Buffer) {
        assertFailsWith<IllegalStateException> {
            buffer.readUnsafe().use { cursor ->
                // 중복된 acquire
                buffer.readUnsafe(cursor)
            }
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `release without acquire`(buffer: Buffer) {
        val cursor = Buffer.UnsafeCursor()
        assertFailsWith<IllegalStateException> {
            cursor.close()
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `releas after release`(buffer: Buffer) {
        val cursor = buffer.readUnsafe()
        cursor.close()
        assertFailsWith<IllegalStateException> {
            cursor.close() // 이미 닫힌 커서를 다시 닫으려 함
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `enlarge buffer`(buffer: Buffer) {
        val originalSize = buffer.size
        val expected = buffer.copy()
        expected.writeUtf8("abc")

        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.resizeBuffer(originalSize + 3L) shouldBeEqualTo originalSize

            cursor.seek(originalSize)
            cursor.data!![cursor.start] = 'a'.asByte()

            cursor.seek(originalSize + 1)
            cursor.data!![cursor.start] = 'b'.asByte()

            cursor.seek(originalSize + 2)
            cursor.data!![cursor.start] = 'c'.asByte()
        }
        buffer shouldBeEqualTo expected
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `enlarge by many segments`(buffer: Buffer) {
        val originalSize = buffer.size
        val expected = buffer.copy()
        expected.writeUtf8("x".repeat(1_000_000))

        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.resizeBuffer(originalSize + 1_000_000L)
            cursor.seek(originalSize)
            do {
                Arrays.fill(cursor.data, cursor.start, cursor.end, 'x'.asByte())
            } while (cursor.next() != -1)
        }
        buffer shouldBeEqualTo expected
    }

    @Test
    fun `resize not acquired`() {
        val cursor = Buffer.UnsafeCursor()
        assertFailsWith<IllegalStateException> {
            cursor.resizeBuffer(10L)
        }
    }

    @Test
    fun `expand not acquired`() {
        val cursor = Buffer.UnsafeCursor()
        assertFailsWith<IllegalStateException> {
            cursor.expandBuffer(10)
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `resize acquired read only`(buffer: Buffer) {

        buffer.readUnsafe().use { cursor ->
            assertFailsWith<IllegalStateException> {
                cursor.resizeBuffer(10L)
            }
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `expand acquired read only`(buffer: Buffer) {
        buffer.readUnsafe().use { cursor ->
            assertFailsWith<IllegalStateException> {
                cursor.expandBuffer(10)
            }
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `shrink buffer`(buffer: Buffer) {
        Assumptions.assumeTrue { buffer.size > 3L }
        val originalSize = buffer.size
        val expected = Buffer()
        buffer.copyTo(expected, 0, originalSize - 3L)

        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.resizeBuffer(originalSize - 3L)
        }
        buffer shouldBeEqualTo expected
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `shrink buffer by many segments`(buffer: Buffer) {
        Assumptions.assumeTrue { buffer.size < 1_000_000L }
        val originalSize = buffer.size

        val toShrink = Buffer()
        toShrink.writeUtf8("x".repeat(1_000_000))
        buffer.copyTo(toShrink, 0, originalSize)

        val cursor = Buffer.UnsafeCursor()
        toShrink.readAndWriteUnsafe(cursor)
        try {
            cursor.resizeBuffer(originalSize)
        } finally {
            cursor.close()
        }
        val expected = bufferOf("x".repeat(originalSize.toInt()))
        toShrink shouldBeEqualTo expected
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `shrink adjust offset`(buffer: Buffer) {
        Assumptions.assumeTrue { buffer.size > 4L }

        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.seek(buffer.size - 1L)
            cursor.resizeBuffer(3)
            cursor.offset shouldBeEqualTo 3L
            cursor.data.shouldBeNull()
            cursor.start shouldBeEqualTo -1
            cursor.end shouldBeEqualTo -1
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `resize to same size seeks to end`(buffer: Buffer) {
        val originalSize = buffer.size
        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.seek(originalSize / 2)
            buffer.size shouldBeEqualTo originalSize

            cursor.resizeBuffer(originalSize)
            buffer.size shouldBeEqualTo originalSize
            cursor.offset shouldBeEqualTo originalSize

            cursor.data.shouldBeNull()
            cursor.start shouldBeEqualTo -1
            cursor.end shouldBeEqualTo -1
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `resize enlarge moves cursor to old size`(buffer: Buffer) {
        val originalSize = buffer.size
        val expected = buffer.copy()
        expected.writeUtf8("a")

        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.seek(originalSize / 2)
            buffer.size shouldBeEqualTo originalSize

            cursor.resizeBuffer(originalSize + 1L)
            cursor.offset shouldBeEqualTo originalSize

            cursor.data.shouldNotBeNull()
            cursor.start shouldNotBeEqualTo -1
            cursor.end shouldBeEqualTo (cursor.start + 1)

            cursor.data!![cursor.start] = 'a'.asByte()
        }
        buffer shouldBeEqualTo expected
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `resize shrink moves cursor to end`(buffer: Buffer) {
        Assumptions.assumeTrue { buffer.size > 0L }

        val originalSize = buffer.size

        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.seek(originalSize / 2)
            buffer.size shouldBeEqualTo originalSize

            cursor.resizeBuffer(originalSize - 1L)
            cursor.offset shouldBeEqualTo originalSize - 1L

            cursor.data.shouldBeNull()
            cursor.start shouldBeEqualTo -1
            cursor.end shouldBeEqualTo -1
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `expand cursor`(buffer: Buffer) {
        val originalSize = buffer.size
        val expected = buffer.copy()
        expected.writeUtf8("abcde")

        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.expandBuffer(5)
            repeat(5) {
                cursor.data!![cursor.start + it] = ('a'.code + it).toByte()
            }
            cursor.resizeBuffer(originalSize + 5L)
        }

        buffer shouldBeEqualTo expected
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `expand same segment`(buffer: Buffer) {
        Assumptions.assumeTrue { buffer.size > 0L }

        val originalSize = buffer.size
        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.seek(originalSize - 1L)
            val originalEnd = cursor.end
            Assumptions.assumeTrue { originalEnd < SEGMENT_SIZE }

            val addedByteCount = cursor.expandBuffer(1)
            addedByteCount shouldBeEqualTo (SEGMENT_SIZE - originalEnd).toLong()
            buffer.size shouldBeEqualTo originalSize + addedByteCount
            cursor.start shouldBeEqualTo originalEnd
            cursor.end shouldBeEqualTo SEGMENT_SIZE
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `expand new segment`(buffer: Buffer) {
        val originalSize = buffer.size
        buffer.readAndWriteUnsafe().use { cursor ->
            val addedByteCount = cursor.expandBuffer(SEGMENT_SIZE)

            addedByteCount shouldBeEqualTo SEGMENT_SIZE.toLong()
            cursor.offset shouldBeEqualTo originalSize
            cursor.start shouldBeEqualTo 0
            cursor.end shouldBeEqualTo SEGMENT_SIZE
        }
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `expand moves offset to old size`(buffer: Buffer) {
        val originalSize = buffer.size

        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.seek(buffer.size / 2)
            buffer.size shouldBeEqualTo originalSize

            val addedByteCount = cursor.expandBuffer(5)
            buffer.size shouldBeEqualTo originalSize + addedByteCount
            cursor.offset shouldBeEqualTo originalSize
        }
    }
}
