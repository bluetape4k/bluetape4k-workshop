package io.bluetape4k.okio.channels

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import okio.Buffer
import okio.Source
import okio.Timeout
import java.nio.channels.FileChannel

fun FileChannel.asSource(timeout: Timeout = Timeout.NONE): Source =
    FileChannelSource(this, timeout)

class FileChannelSource(
    private val channel: FileChannel,
    private val timeout: Timeout = Timeout.NONE,
): Source {

    companion object: KLogging()

    private var position = channel.position()

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (!channel.isOpen) error("Channel is closed")
        if (position == channel.size()) return -1L
        timeout.throwIfReached()

        val read = channel.transferTo(position, byteCount, sink)
        log.debug { "Read $read bytes from channel at position $position" }

        position += read
        return read
    }

    override fun timeout(): Timeout = timeout

    override fun close() {
        channel.close()
    }
}
