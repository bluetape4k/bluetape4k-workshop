package io.bluetape4k.okio.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireZeroOrPositiveNumber
import okio.Buffer
import java.nio.channels.AsynchronousFileChannel

fun AsynchronousFileChannel.asSuspendedSink(): SuspendedSink =
    SuspendedFileChannelSink(this)

class SuspendedFileChannelSink(
    private val channel: AsynchronousFileChannel,
): SuspendedSink {

    companion object: KLoggingChannel()

    private val byteBuffer = Buffer()

    override suspend fun write(source: Buffer, byteCount: Long) {
        byteCount.requireZeroOrPositiveNumber("byteCount")

        TODO("SuspendedSocketSink 를 참고해서 구현해야 합니다.")
    }

    override suspend fun flush() {
        // Cannot alter meta data through this sink
        channel.force(false)
    }

    override suspend fun close() {
        if (!channel.isOpen) return

        log.debug { "Closing file channel" }
        channel.close()
    }
}
