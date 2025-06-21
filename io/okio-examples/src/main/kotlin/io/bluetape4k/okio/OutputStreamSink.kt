package io.bluetape4k.okio

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireInRange
import okio.Buffer
import okio.Sink
import okio.Timeout
import java.io.OutputStream

fun OutputStream.asSink(timeout: Timeout = Timeout.NONE): Sink =
    OutputStreamSink(this, timeout)

class OutputStreamSink(
    private val out: OutputStream,
    private val timeout: Timeout = Timeout.NONE,
): Sink {

    companion object: KLogging()

    override fun write(source: Buffer, byteCount: Long) {
        source.size.requireInRange(0, byteCount, "source.size")

        val cursor = Buffer.UnsafeCursor()
        var remaining = byteCount
        while (remaining > 0) {
            timeout.throwIfReached()

            source.readUnsafe(cursor).use { ignored ->
                cursor.seek(0)
                val length = minOf(cursor.end - cursor.start, remaining.toInt())
                out.write(cursor.data, cursor.start, length)
                log.debug { "Wrote $length bytes to channel at position=${cursor.start}" }
                remaining -= length.toLong()
                source.skip(length.toLong())
            }
        }
    }

    override fun flush() {
        out.flush()
    }

    override fun timeout(): Timeout = timeout

    override fun close() {
        out.close()
    }

    override fun toString(): String = "OutStreamSink($out)"
}
