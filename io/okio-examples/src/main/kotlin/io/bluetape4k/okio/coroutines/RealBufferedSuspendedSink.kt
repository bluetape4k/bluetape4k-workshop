package io.bluetape4k.okio.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.okio.SEGMENT_SIZE
import okio.Buffer
import okio.ByteString
import okio.Timeout
import java.util.concurrent.atomic.AtomicBoolean

internal class RealBufferedSuspendedSink(private val sink: SuspendedSink): BufferedSuspendedSink {

    companion object: KLoggingChannel()

    override val buffer: Buffer = Buffer()

    private val closed = AtomicBoolean(false)

    override suspend fun write(byteString: ByteString): BufferedSuspendedSink = emitCompleteSegments {
        buffer.write(byteString)
    }

    override suspend fun write(
        source: ByteArray,
        offset: Int,
        byteCount: Int,
    ): BufferedSuspendedSink = emitCompleteSegments {
        buffer.write(source, offset, byteCount)
    }

    override suspend fun write(
        source: SuspendedSource,
        byteCount: Long,
    ): BufferedSuspendedSink = apply {
        checkNotClosed()
        var remaining = byteCount
        while (remaining > 0L) {
            val read = source.read(buffer, remaining)
            if (read == -1L) throw okio.EOFException()
            remaining -= read
            emitCompleteSegments()
        }
    }

    override suspend fun write(source: Buffer, byteCount: Long) {
        emitCompleteSegments {
            buffer.write(source, byteCount)
        }
    }

    override suspend fun writeAll(source: SuspendedSource): Long {
        checkNotClosed()
        var totalBytesRead = 0L
        while (true) {
            val readCount = source.read(buffer, SEGMENT_SIZE)
            if (readCount == -1L) break
            totalBytesRead += readCount
            emitCompleteSegments()
        }
        return totalBytesRead
    }


    override suspend fun writeUtf8(
        string: String,
        beginIndex: Int,
        endIndex: Int,
    ): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeUtf8(string, beginIndex, endIndex)
    }

    override suspend fun writeUtf8CodePoint(codePoint: Int): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeUtf8CodePoint(codePoint)
    }

    override suspend fun writeByte(b: Int): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeByte(b)
    }

    override suspend fun writeShort(s: Int): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeShort(s)
    }

    override suspend fun writeShortLe(s: Int): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeShortLe(s)
    }

    override suspend fun writeInt(i: Int): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeInt(i)
    }

    override suspend fun writeIntLe(i: Int): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeIntLe(i)
    }

    override suspend fun writeLong(v: Long): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeLong(v)
    }

    override suspend fun writeLongLe(v: Long): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeLongLe(v)
    }

    override suspend fun writeDecimalLong(v: Long): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeDecimalLong(v)
    }

    override suspend fun writeHexadecimalUnsignedLong(v: Long): BufferedSuspendedSink = emitCompleteSegments {
        buffer.writeHexadecimalUnsignedLong(v)
    }

    override suspend fun flush() {
        checkNotClosed()
        if (buffer.size > 0L) {
            sink.write(buffer, buffer.size)
        }
        sink.flush()
    }

    override suspend fun emit(): BufferedSuspendedSink = apply {
        checkNotClosed()
        val byteCount = buffer.size
        if (byteCount > 0L) {
            sink.write(buffer, byteCount)
        }
    }

    override suspend fun emitCompleteSegments(): BufferedSuspendedSink = emitCompleteSegments {
        // Noting to do
    }


    override suspend fun close() {
        if (closed.get()) {
            return
        }

        // Emit buffered data to the underlying sink. If this fails, we still need
        // to close the sink; otherwise we risk leaking resources.
        var thrown: Throwable? = null
        try {
            if (buffer.size > 0L) {
                sink.write(buffer, buffer.size)
            }
        } catch (e: Throwable) {
            thrown = e
        }
        try {
            sink.close()
        } catch (e: Throwable) {
            thrown = thrown ?: e
        }

        closed.set(true)

        if (thrown != null) {
            throw thrown
        }
    }

    override fun timeout(): Timeout = sink.timeout()

    private suspend inline fun emitCompleteSegments(block: suspend () -> Unit): BufferedSuspendedSink = apply {
        checkNotClosed()
        block()
        val byteCount = buffer.completeSegmentByteCount()
        if (byteCount > 0L) {
            sink.write(buffer, byteCount)
        }
    }

    private fun checkNotClosed() {
        check(!closed.get()) { "RealBufferedSuspendedSink is already closed" }
    }

    override fun toString(): String {
        return "RealBufferedSuspendedSink(sink=$sink)"
    }
}
