package io.bluetape4k.workshop.protobuf.convert

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.protobuf.serializers.ProtobufSerializer
import io.bluetape4k.workshop.protobuf.course
import org.junit.jupiter.api.Test
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException

class ProtobufByteBufferConverterTest {

    private val course = course {
        id = 7
        courseName = "Caller-owned buffer"
    }
    private val allocatingWire = ProtobufSerializer().serialize(course)

    @Test
    fun `serializeTo writes allocating wire bytes to heap and direct targets`() {
        listOf(
            ByteBuffer.allocate(allocatingWire.size + 8),
            ByteBuffer.allocateDirect(allocatingWire.size + 8),
        ).forEach { target ->
            target.position(4)

            course.serializeTo(target) shouldBeEqualTo allocatingWire.size
            target.position() shouldBeEqualTo 4 + allocatingWire.size
            target.bytesBetween(4, target.position())
                .contentEquals(allocatingWire)
                .shouldBeTrue()
        }
    }

    @Test
    fun `serializeTo reuses caller buffer after clear`() {
        val target = ByteBuffer.allocate(allocatingWire.size)

        repeat(3) {
            target.clear()
            course.serializeTo(target) shouldBeEqualTo allocatingWire.size
            target.flip()
            target.bytesBetween(0, target.limit())
                .contentEquals(allocatingWire)
                .shouldBeTrue()
        }
    }

    @Test
    fun `serializeTo preserves position and content when capacity is insufficient`() {
        val target = ByteBuffer.allocate(allocatingWire.size).apply {
            while (hasRemaining()) put(0x5A.toByte())
            position(1)
        }
        val start = target.position()
        val content = target.allBytes()

        assertFailsWith<BufferOverflowException> {
            course.serializeTo(target)
        }

        target.position() shouldBeEqualTo start
        target.allBytes().contentEquals(content).shouldBeTrue()
    }

    @Test
    fun `serializeTo preserves read only target position and content`() {
        val target = ByteBuffer.allocate(allocatingWire.size + 4).apply {
            while (hasRemaining()) put(0x5A.toByte())
        }
            .asReadOnlyBuffer()
            .apply { position(2) }
        val start = target.position()
        val content = target.allBytes()

        assertFailsWith<ReadOnlyBufferException> {
            course.serializeTo(target)
        }

        target.position() shouldBeEqualTo start
        target.allBytes().contentEquals(content).shouldBeTrue()
    }

    private fun ByteBuffer.bytesBetween(start: Int, end: Int): ByteArray =
        ByteArray(end - start).also { bytes ->
            duplicate().apply {
                position(start)
                limit(end)
            }.get(bytes)
        }

    private fun ByteBuffer.allBytes(): ByteArray = bytesBetween(0, capacity())
}
