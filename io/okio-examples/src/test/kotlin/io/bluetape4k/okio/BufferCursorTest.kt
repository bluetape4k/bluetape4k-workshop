package io.bluetape4k.okio

import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import okio.Buffer
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*

class BufferCursorTest: AbstractOkioTest() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5

        private val _factories = BufferFactory.entries.toList()
        private val _buffers = _factories.asSequence().map { it.newBuffer() }
    }

    fun factories() = _factories
    fun buffers() = _buffers

    @Test
    fun `api example`() {
        val buffer = Buffer()
        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.resizeBuffer(1_000_000L)
            do {
                Arrays.fill(cursor.data!!, cursor.start, cursor.end, 'x'.code.toByte())
            } while (cursor.next() != -1)

            cursor.seek(3)
            cursor.data!![cursor.start] = 'o'.code.toByte()
            cursor.seek(1)
            cursor.data!![cursor.start] = 'o'.code.toByte()
            cursor.resizeBuffer(4)
        }
        log.debug { "buffer=$buffer" }
        buffer shouldBeEqualTo bufferOf("xoxo")
    }

    @Test
    fun `api example by kotlin function`() {
        val buffer = Buffer()
        buffer.readAndWriteUnsafe().use { cursor ->
            cursor.resizeBuffer(1_000_000)
            val x = 'x'.code.toByte()
            val data = cursor.data!!
            do {
                data.fill(x, cursor.start, cursor.end)
            } while (cursor.next() != -1)
            cursor.seek(3)
            data[cursor.start] = 'o'.code.toByte()
            cursor.seek(1)
            data[cursor.start] = 'o'.code.toByte()
            cursor.resizeBuffer(4)
        }
        log.debug { "buffer=$buffer" }
        buffer shouldBeEqualTo bufferOf("xoxo")
    }

    @ParameterizedTest
    @MethodSource("buffers")
    fun `access segment by segment`(buffer: Buffer) {
        buffer.readUnsafe().use { cursor ->
            val actual = Buffer()
            while (cursor.next() != -1) {
                actual.write(cursor.data!!, cursor.start, cursor.end - cursor.start)
            }
            actual shouldBeEqualTo buffer
        }
    }
}
