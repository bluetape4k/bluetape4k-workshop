package io.bluetape4k.okio.channels

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import okio.Buffer
import okio.Sink
import okio.Timeout
import java.nio.channels.FileChannel

fun FileChannel.asSink(timeout: Timeout = Timeout.NONE): Sink =
    FileChannelSink(this, timeout)

class FileChannelSink(
    private val channel: FileChannel,
    private val timeout: Timeout = Timeout.NONE,
): Sink {

    companion object: KLogging()

    private var position = channel.position()

    override fun write(source: Buffer, byteCount: Long) {
        if (!channel.isOpen) error("Channel is closed")
        if (byteCount <= 0) return
        timeout.throwIfReached()

        var remaining = byteCount
        while (remaining > 0) {
            val written = channel.transferFrom(source, position, remaining)
            log.debug { "Wrote $written bytes to channel at position $position" }
            position += written
            remaining -= written
        }
    }

    override fun flush() {
        // Cannot alter meta data through this sink 
        channel.force(false)
    }

    override fun timeout(): Timeout = timeout

    override fun close() {
        channel.close()
    }
}
