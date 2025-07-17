package io.bluetape4k.okio.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireZeroOrPositiveNumber
import okio.Buffer
import java.nio.channels.AsynchronousFileChannel

class FileChannelSuspendedSource(
    private val channel: AsynchronousFileChannel,
): SuspendedSource {

    companion object: KLoggingChannel()


    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        byteCount.requireZeroOrPositiveNumber("byteCount")

        TODO("SuspendedSocketSource 를 참고해서 구현해야 합니다.")
    }

    override suspend fun close() {
        if (!channel.isOpen) return

        log.debug { "Closing file channel" }
        channel.close()
    }
}
