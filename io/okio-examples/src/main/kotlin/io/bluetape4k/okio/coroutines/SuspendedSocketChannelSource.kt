package io.bluetape4k.okio.coroutines

import io.bluetape4k.coroutines.support.suspendAwait
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.okio.SEGMENT_SIZE
import okio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel

fun AsynchronousSocketChannel.asSuspendedSource(): SuspendedSource =
    SuspendedSocketChannelSource(this)

class SuspendedSocketChannelSource(
    private val channel: AsynchronousSocketChannel,
): SuspendedSource {

    companion object: KLoggingChannel()

    private val byteBuffer = ByteBuffer.allocateDirect(SEGMENT_SIZE.toInt())

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount must be zero or positive, but was $byteCount" }

        if (!channel.isOpen) return -1L

        byteBuffer.clear()
        byteBuffer.limit(minOf(SEGMENT_SIZE, byteCount).toInt())

        val read = channel.read(byteBuffer).suspendAwait()

        if (read > 0) {
            sink.write(byteBuffer)
        }

        return read.toLong()
    }

    override suspend fun close() {
        if (channel.isOpen) {
            log.debug { "Closing socket channel" }
            channel.close()
        }
    }
}        
