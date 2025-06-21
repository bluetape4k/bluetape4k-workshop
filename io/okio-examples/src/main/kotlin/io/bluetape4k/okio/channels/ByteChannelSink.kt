package io.bluetape4k.okio.channels

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import okio.Buffer
import okio.Sink
import okio.Timeout
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

fun WritableByteChannel.asSink(timeout: Timeout = Timeout.NONE): Sink =
    ByteChannelSink(this, timeout)

class ByteChannelSink(
    private val channel: WritableByteChannel,
    private val timeout: Timeout = Timeout.NONE,
): Sink {

    companion object: KLogging()

    override fun write(source: Buffer, byteCount: Long) {
        if (!channel.isOpen) error("Channel is closed")
        if (byteCount <= 0L) return

        val cursor = Buffer.UnsafeCursor()
        var remaining = byteCount

        while (remaining > 0) {
            timeout.throwIfReached()

            source.readUnsafe(cursor).use { ignored ->
                cursor.seek(0)
                val length = minOf(cursor.end - cursor.start, remaining.toInt())
                val written = channel.write(ByteBuffer.wrap(cursor.data, cursor.start, length))
                log.debug { "Wrote $written bytes to channel at position=${cursor.start}" }
                remaining -= written.toLong()
                source.skip(written.toLong())
            }
        }
    }

    override fun flush() {}

    override fun timeout(): Timeout = timeout

    override fun close() {
        channel.close()
    }
}
