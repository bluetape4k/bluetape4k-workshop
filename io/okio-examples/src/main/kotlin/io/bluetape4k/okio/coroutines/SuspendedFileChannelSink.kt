package io.bluetape4k.okio.coroutines

import io.bluetape4k.coroutines.support.suspendAwait
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import okio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel

fun AsynchronousFileChannel.asSuspendedSink(): SuspendedSink =
    SuspendedFileChannelSink(this)

class SuspendedFileChannelSink(
    private val channel: AsynchronousFileChannel,
): SuspendedSink {

    companion object: KLoggingChannel()

    private var position = 0L

    override suspend fun write(source: Buffer, byteCount: Long) {
        if (!channel.isOpen) error("Channel is closed")
        if (byteCount == 0L) return
        timeout().throwIfReached()

        val length = minOf(source.size, byteCount)
        val byteBuffer = ByteBuffer.wrap(source.readByteArray(length))
        val byteWritten = channel.write(byteBuffer, position).suspendAwait()
        position += byteWritten
        log.debug { "Wrote $byteWritten bytes to channel at position $position" }

//        source.readUnsafe().use { cursor ->
//            var remaining = byteCount
//            while (remaining > 0) {
//                cursor.seek(0)
//                val length = minOf(cursor.end - cursor.start, remaining.toInt())
//                val buffer = ByteBuffer.wrap(cursor.data, cursor.start, length)
//                val bytesWritten = channel.write(buffer, position).suspendAwait()
//
//                log.debug { "Wrote $bytesWritten bytes to channel at position $position" }
//
//                position += bytesWritten
//                remaining -= bytesWritten.toLong()
//                source.skip(bytesWritten.toLong())
//            }
//        }
    }

    override suspend fun flush() {
        // Cannot alter meta data through this sink
        channel.force(false)
    }

    override suspend fun close() {
        if (!channel.isOpen) return
        log.debug { "Closing AsynchronousFileChannel[$channel]" }
        channel.close()
    }
}
