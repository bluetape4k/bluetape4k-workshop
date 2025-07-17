package io.bluetape4k.okio.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.okio.SEGMENT_SIZE
import io.bluetape4k.okio.coroutines.internal.await
import io.bluetape4k.support.requireZeroOrPositiveNumber
import okio.Buffer
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey

class SocketSuspendedSource(socket: Socket): SuspendedSource {

    companion object: KLoggingChannel()

    private val channel = socket.channel
    private val byteBuffer = ByteBuffer.allocateDirect(SEGMENT_SIZE.toInt())

    override suspend fun read(sink: Buffer, byteCount: Long): Long {

        byteCount.requireZeroOrPositiveNumber("byteCount")

        channel.await(SelectionKey.OP_READ)

        byteBuffer.clear()
        byteBuffer.limit(minOf(SEGMENT_SIZE, byteCount).toInt())

        val read = channel.read(byteBuffer)
        byteBuffer.flip()

        if (read > 0) {
            sink.write(byteBuffer)
        }

        return read.toLong()
    }

    override suspend fun close() {
        if (!channel.isOpen) return
        log.debug { "Closing socket channel" }

        channel.close()
    }
}
