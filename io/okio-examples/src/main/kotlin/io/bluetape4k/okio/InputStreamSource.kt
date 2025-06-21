package io.bluetape4k.okio

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import okio.Buffer
import okio.Source
import okio.Timeout
import java.io.InputStream

fun InputStream.asSource(timeout: Timeout = Timeout.NONE): Source =
    InputStreamSource(this, timeout)

class InputStreamSource(
    private val input: java.io.InputStream,
    private val timeout: okio.Timeout = okio.Timeout.NONE,
): Source {

    companion object: KLogging()

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0L

        val cursor = Buffer.UnsafeCursor()
        sink.readAndWriteUnsafe(cursor).use { ignored ->
            timeout.throwIfReached()

            val originalSize = sink.size
            val length = byteCount.toInt().coerceAtMost(input.available())

            cursor.expandBuffer(byteCount.toInt())
            val read = input.read(cursor.data, cursor.start, length)
            log.debug { "Read $read bytes from channel at position=${cursor.start}" }
            if (read == -1) {
                cursor.resizeBuffer(originalSize)
                return -1L
            } else {
                cursor.resizeBuffer(originalSize + length)
                return read.toLong()
            }
        }
    }

    override fun timeout(): Timeout = timeout

    override fun close() {
        input.close()
    }
}
