package io.bluetape4k.okio.coroutines

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.okio.AbstractOkioTest
import io.bluetape4k.okio.SEGMENT_SIZE
import io.bluetape4k.okio.bufferOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.Timeout
import org.junit.jupiter.api.Test
import java.io.IOException

class BufferedSuspendedSinkTest: AbstractOkioTest() {

    companion object: KLoggingChannel()

    @Test
    fun `emitCompleteSegments keeps complete segments delegated and tail internal`() = runTest {
        val fakeSink = FakeSuspendedSink()
        val bufferedSink: BufferedSuspendedSink = fakeSink.buffered()
        val completeSegment = ByteArray(SEGMENT_SIZE) { it.toByte() }
        val tail = "tail-after-complete-segment".encodeUtf8()
        val expectedDelegate = Buffer().apply { write(completeSegment) }.snapshot()

        bufferedSink.write(completeSegment, 0, completeSegment.size)
        fakeSink.buffer.snapshot() shouldBeEqualTo expectedDelegate
        bufferedSink.buffer.snapshot() shouldBeEqualTo ByteString.EMPTY

        bufferedSink.write(tail)
        fakeSink.buffer.snapshot() shouldBeEqualTo expectedDelegate
        bufferedSink.buffer.snapshot() shouldBeEqualTo tail
        fakeSink.flushCount shouldBeEqualTo 0
        fakeSink.closeCount shouldBeEqualTo 0
    }

    @Test
    fun `flush transfers one tail and close transfers subsequent tail exactly once`() = runTest {
        val fakeSink = FakeSuspendedSink()
        val bufferedSink = fakeSink.buffered()
        val firstTail = "tail-before-flush".encodeUtf8()
        val secondTail = "tail-before-close".encodeUtf8()
        val expected = Buffer().apply {
            write(firstTail)
            write(secondTail)
        }.snapshot()

        bufferedSink.write(firstTail)
        bufferedSink.flush()
        bufferedSink.write(secondTail)
        bufferedSink.close()
        bufferedSink.close()

        fakeSink.buffer.snapshot() shouldBeEqualTo expected
        bufferedSink.buffer.snapshot() shouldBeEqualTo ByteString.EMPTY
        fakeSink.flushCount shouldBeEqualTo 1
        fakeSink.closeCount shouldBeEqualTo 1
    }

    @Test
    fun `all buffered write overloads preserve exact payload`() = runTest {
        val fakeSink = FakeSuspendedSink()
        val bufferedSink = fakeSink.buffered()
        val expected = Buffer()
        val writeAllPayload = "write-all-sentinel"

        bufferedSink.writeByteStringAndTextOverloads(expected)
        bufferedSink.writeNumericOverloads(expected)
        val writeAllCount = bufferedSink.writeSourceOverloads(expected, writeAllPayload)
        writeAllCount shouldBeEqualTo writeAllPayload.encodeUtf8().size.toLong()

        bufferedSink.emitCompleteSegments()
        bufferedSink.emit()
        bufferedSink.flush()

        fakeSink.buffer.snapshot() shouldBeEqualTo expected.snapshot()
        bufferedSink.buffer.snapshot() shouldBeEqualTo ByteString.EMPTY
        fakeSink.flushCount shouldBeEqualTo 1
    }

    @Test
    fun `write after close throws`() = runTest {
        val bufferedSink = FakeSuspendedSink().buffered()
        bufferedSink.close()

        assertFailsWith<IllegalStateException> {
            bufferedSink.writeUtf8("fail", 0, 4)
        }
    }

    @Test
    fun `write from suspended source fails after eight no progress reads`() = runTest {
        val source = NoProgressSuspendedSource()

        val error = assertFailsWith<IOException> {
            withTimeout(250) {
                FakeSuspendedSink().buffered().write(source, 1L)
            }
        }

        error.message shouldBeEqualTo "Unable to write from SuspendedSource: no progress."
        source.readCount shouldBeEqualTo 8
    }

    @Test
    fun `writeAll from suspended source fails after eight no progress reads`() = runTest {
        val source = NoProgressSuspendedSource()

        val error = assertFailsWith<IOException> {
            withTimeout(250) {
                FakeSuspendedSink().buffered().writeAll(source)
            }
        }

        error.message shouldBeEqualTo "Unable to writeAll from SuspendedSource: no progress."
        source.readCount shouldBeEqualTo 8
    }

    @Test
    fun `close preserves first write failure and still closes underlying sink once`() = runTest {
        val payload = "sensitive-tail-payload"
        val writeFailure = IOException("buffered write failed")
        val closeFailure = IOException("underlying close failed")
        val fakeSink = FailingSuspendedSink(writeFailure, closeFailure)
        val bufferedSink = fakeSink.buffered()
        bufferedSink.writeUtf8(payload)

        val error = assertFailsWith<IOException> {
            bufferedSink.close()
        }

        error shouldBeSameInstanceAs writeFailure
        error.message.orEmpty() shouldNotContain payload
        error.suppressed.size shouldBeEqualTo 0
        fakeSink.writeCount shouldBeEqualTo 1
        fakeSink.closeCount shouldBeEqualTo 1

        bufferedSink.close()
        fakeSink.writeCount shouldBeEqualTo 1
        fakeSink.closeCount shouldBeEqualTo 1
    }

    private suspend fun BufferedSuspendedSink.writeByteStringAndTextOverloads(expected: Buffer) {
        val byteString = "byte-string-sentinel".encodeUtf8()
        write(byteString)
        expected.write(byteString)

        val byteArray = byteArrayOf(0x21, 0x22, 0x23, 0x24, 0x25)
        write(byteArray)
        expected.write(byteArray)
        write(byteArray, 1, 3)
        expected.write(byteArray, 1, 3)

        val fullUtf8 = "utf8-full-sentinel-한글"
        writeUtf8(fullUtf8)
        expected.writeUtf8(fullUtf8)

        val rangedUtf8 = "prefix-utf8-range-suffix"
        writeUtf8(rangedUtf8, 7, 17)
        expected.writeUtf8(rangedUtf8, 7, 17)

        val codePoint = 0x1F642
        writeUtf8CodePoint(codePoint)
        expected.writeUtf8CodePoint(codePoint)
    }

    private suspend fun BufferedSuspendedSink.writeNumericOverloads(expected: Buffer) {
        writeByte(0x2A)
        expected.writeByte(0x2A)
        writeShort(0x3142)
        expected.writeShort(0x3142)
        writeShortLe(0x4354)
        expected.writeShortLe(0x4354)
        writeInt(0x51525354)
        expected.writeInt(0x51525354)
        writeIntLe(0x61626364)
        expected.writeIntLe(0x61626364)
        writeLong(0x0102030405060708L)
        expected.writeLong(0x0102030405060708L)
        writeLongLe(0x1112131415161718L)
        expected.writeLongLe(0x1112131415161718L)
        writeDecimalLong(-9876543210L)
        expected.writeDecimalLong(-9876543210L)
        writeHexadecimalUnsignedLong(0x1234ABCDL)
        expected.writeHexadecimalUnsignedLong(0x1234ABCDL)
    }

    private suspend fun BufferedSuspendedSink.writeSourceOverloads(expected: Buffer, writeAllPayload: String): Long {
        val bufferSource = bufferOf("buffer-source-sentinel")
        val expectedBufferSource = bufferOf("buffer-source-sentinel")
        write(bufferSource, 6L)
        expected.write(expectedBufferSource, 6L)

        val fixedSource: okio.Source = bufferOf("fixed-source-sentinel")
        val expectedFixedSource = bufferOf("fixed-source-sentinel")
        write(fixedSource.asSuspended(), 7L)
        expected.write(expectedFixedSource, 7L)

        val writeAllSource: okio.Source = bufferOf(writeAllPayload)
        val expectedWriteAllSource = bufferOf(writeAllPayload)
        val writeAllCount = writeAll(writeAllSource.asSuspended())
        expected.write(expectedWriteAllSource, expectedWriteAllSource.size)
        return writeAllCount
    }

    private class NoProgressSuspendedSource: SuspendedSource {
        var readCount = 0
            private set

        override suspend fun read(sink: Buffer, byteCount: Long): Long {
            readCount++
            delay(10)
            return 0L
        }

        override suspend fun close() = Unit

        override fun timeout() = Timeout.NONE
    }

    private open class FakeSuspendedSink: SuspendedSink {
        val buffer = Buffer()
        var flushCount = 0
            private set
        var closeCount = 0
            protected set
        var writeCount = 0
            protected set

        override suspend fun write(source: Buffer, byteCount: Long) {
            writeCount++
            buffer.write(source, byteCount)
        }

        override suspend fun flush() {
            flushCount++
        }

        override suspend fun close() {
            closeCount++
        }

        override fun timeout() = Timeout.NONE
    }

    private class FailingSuspendedSink(
        private val writeFailure: IOException,
        private val closeFailure: IOException,
    ): FakeSuspendedSink() {
        override suspend fun write(source: Buffer, byteCount: Long) {
            writeCount++
            throw writeFailure
        }

        override suspend fun close() {
            closeCount++
            throw closeFailure
        }
    }
}
