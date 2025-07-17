package io.bluetape4k.okio.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import kotlin.coroutines.CoroutineContext

class SocketChannelSuspendedSink(
    private val channel: AsynchronousSocketChannel,
    private val context: CoroutineContext = Dispatchers.IO,
): SuspendedSink {

    companion object: KLoggingChannel()

    private val cursor = Buffer.UnsafeCursor()

    override suspend fun write(source: Buffer, byteCount: Long) {
        withContext(context) {
            source.readUnsafe(cursor).use { cursor ->
                val byteBuffer = ByteBuffer.allocate(byteCount.toInt())
                val offset = if (cursor.start < 0) 0 else cursor.start
                byteBuffer.put(cursor.data, offset, byteCount.toInt())
                channel.write(byteBuffer)
            }
        }
    }

    override suspend fun flush() {
        TODO("Not yet implemented")
    }

    override suspend fun close() {
        withContext(context) {
            if (!channel.isOpen) return@withContext
            channel.close()
            log.debug { "Closing file channel" }
        }
    }
}
