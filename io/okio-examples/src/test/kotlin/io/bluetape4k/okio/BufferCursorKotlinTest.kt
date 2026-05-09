package io.bluetape4k.okio

import io.bluetape4k.logging.KLogging
import okio.Buffer
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class BufferCursorKotlinTest: AbstractOkioTest() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5

        private val _factories = BufferFactory.factories
        private val _buffers = _factories.map { it.newBuffer() }
    }

    fun factories() = _factories
    fun buffers() = _buffers

    @ParameterizedTest
    @MethodSource("factories")
    fun `cursor reuse`(factory: BufferFactory) {
        val cursor = Buffer.UnsafeCursor()

        val buffer1 = factory.newBuffer()
        buffer1.readUnsafe(cursor)
        cursor.buffer shouldBeEqualTo buffer1
        cursor.readWrite.shouldBeFalse()
        cursor.close()
        cursor.buffer.shouldBeNull()

        val buffer2 = factory.newBuffer()
        buffer2.readAndWriteUnsafe(cursor)
        cursor.buffer shouldBeEqualTo buffer2
        cursor.readWrite.shouldBeTrue()
        cursor.close()
        cursor.buffer.shouldBeNull()
    }
}
