package io.bluetape4k.okio.coroutines

import io.bluetape4k.coroutines.support.awaitSuspending
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import okio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel

fun AsynchronousFileChannel.asSuspendedSource(): SuspendedSource =
    SuspendedFileChannelSource(this)

class SuspendedFileChannelSource(
    private val channel: AsynchronousFileChannel,
): SuspendedSource {

    companion object: KLoggingChannel()

    private var position = 0L

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        if (!channel.isOpen) error("Channel is closed")
        if (position == channel.size()) return -1L
        timeout().throwIfReached()

        var remaining = byteCount
        while (remaining > 0) {
            val length = minOf(remaining, DEFAULT_BUFFER_SIZE.toLong())
            val buffer = ByteBuffer.allocate(length.toInt())
            val bytesRead = channel.read(buffer, position).awaitSuspending()

            if (bytesRead <= 0) break // EOF or no data read

            buffer.flip()
            sink.write(buffer)
            log.debug { "Read $bytesRead bytes from channel at position $position" }

            position += bytesRead
            remaining -= bytesRead
        }
        return byteCount - remaining
    }

    override suspend fun close() {
        if (!channel.isOpen) return
        log.debug { "Closing AsynchronousFileChannel[$channel]" }
        channel.close()
    }
}
