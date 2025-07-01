package io.bluetape4k.okio

import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.io.okio.buffered
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.okio.channels.asSource
import io.bluetape4k.support.toBigInt
import io.bluetape4k.support.toUtf8Bytes
import okio.Buffer
import okio.BufferedSink
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encode
import okio.ByteString.Companion.encodeUtf8
import okio.EOFException
import okio.Sink
import okio.Source
import org.amshove.kluent.internal.assertFailsWith
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.ByteBuffer

class BufferedSinkTest: AbstractOkioTest() {

    companion object: KLogging()

    interface Factory {
        fun create(data: Buffer): BufferedSink

        companion object {
            val BUFFER: Factory = object: Factory {
                override fun create(data: Buffer): BufferedSink = data
                override fun toString(): String = "Buffer"
            }
            val REAL_BUFFER_SINK: Factory = object: Factory {
                override fun create(data: Buffer): BufferedSink = (data as Sink).buffered()
                override fun toString(): String = "RealBufferSink"
            }
        }
    }

    fun factories(): List<Factory> = listOf(Factory.BUFFER, Factory.REAL_BUFFER_SINK)

    private fun getSink(factory: Factory, data: Buffer): BufferedSink =
        factory.create(data)

    @ParameterizedTest
    @MethodSource("factories")
    fun `write nothing`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeUtf8("")
        sink.flush()
        data.size shouldBeEqualTo 0L
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write bytes`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeByte(0xab)
        sink.writeByte(0xcd)
        sink.flush()

        data.toString() shouldBeEqualTo "[hex=abcd]"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write last byte in segment`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeUtf8("a".repeat(SEGMENT_SIZE - 1))
        sink.writeByte(0x20)
        sink.writeByte(0x21)
        sink.flush()

        data.readUtf8(SEGMENT_SIZE - 1L) shouldBeEqualTo "a".repeat(SEGMENT_SIZE - 1)
        data.toString() shouldBeEqualTo "[text= !]"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write Int`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeInt(-0x543210ff)
        sink.writeInt(-0x789abcdf)
        sink.flush()

        data.toString() shouldBeEqualTo "[hex=abcdef0187654321]"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write IntLe`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeIntLe(-0x543210ff)
        sink.writeIntLe(-0x789abcdf)
        sink.flush()

        data.toString() shouldBeEqualTo "[hex=01efcdab21436587]"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write last integer in segment`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeUtf8("a".repeat(SEGMENT_SIZE - 4))
        sink.writeInt(-0x543210ff)
        sink.writeInt(-0x789abcdf)
        sink.flush()

        data.readUtf8(SEGMENT_SIZE - 4L) shouldBeEqualTo "a".repeat(SEGMENT_SIZE - 4)
        data.toString() shouldBeEqualTo "[hex=abcdef0187654321]"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write Long`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeLong(-0x543210fe789abcdfL)
        sink.writeLong(-0x350145414f4ea400L)
        sink.flush()

        data.toString() shouldBeEqualTo "[hex=abcdef0187654321cafebabeb0b15c00]"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write LongLe`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeLongLe(-0x543210fe789abcdfL)
        sink.writeLongLe(-0x350145414f4ea400L)
        sink.flush()

        data.toString() shouldBeEqualTo "[hex=2143658701efcdab005cb1b0bebafeca]"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write ByteString`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.write("동해물과 백두산이".toUtf8Bytes())
        sink.flush()

        val readString = data.readByteString()
        log.debug { "Read string=$readString" }

        readString shouldBeEqualTo "동해물과 백두산이".encodeUtf8()
        readString shouldBeEqualTo "eb8f99ed95b4ebacbceab3bc20ebb0b1eb9190ec82b0ec9db4".decodeHex()
        readString.hex() shouldBeEqualTo "eb8f99ed95b4ebacbceab3bc20ebb0b1eb9190ec82b0ec9db4"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write ByteString Offset`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.write("동해물과 백두산이".toUtf8Bytes(), 5, 5)
        sink.flush()

        val readString = data.readByteString()
        log.debug { "Read string=$readString" }

        readString.hex() shouldBeEqualTo "b4ebacbcea"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write segmented ByteString`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.write(bufferOf("동해물과 백두산이".toUtf8Bytes()).snapshot())
        sink.flush()

        val readString = data.readByteString()
        log.debug { "Read string=$readString" }

        readString.utf8() shouldBeEqualTo "동해물과 백두산이"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write segmented ByteString offset`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.write(bufferOf("동해물과 백두산이".toUtf8Bytes()).snapshot(), 5, 5)
        sink.flush()

        val readString = data.readByteString()
        log.debug { "Read string=$readString" }

        readString.hex() shouldBeEqualTo "b4ebacbcea"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write String UTF8`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeUtf8("동해물과 백두산이")
        sink.flush()

        val readString = data.readByteString()
        log.debug { "Read string=$readString" }

        readString.utf8() shouldBeEqualTo "동해물과 백두산이"
        readString.hex() shouldBeEqualTo "eb8f99ed95b4ebacbceab3bc20ebb0b1eb9190ec82b0ec9db4"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write sub string UTF8`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeUtf8("동해물과 백두산이", 3, 7)
        sink.flush()

        val readString = data.readByteString()
        log.debug { "Read string=$readString" }

        readString.utf8() shouldBeEqualTo "과 백두"
        readString.hex() shouldBeEqualTo "eab3bc20ebb0b1eb9190"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write string with charset`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeString("동해물과 백두산이", Charsets.UTF_32BE)
        sink.flush()

        val readString = data.readByteString()
        log.debug { "Read string=$readString" }

        readString shouldBeEqualTo "동해물과 백두산이".encode(Charsets.UTF_32BE)
        readString.hex() shouldBeEqualTo "0000b3d90000d5740000bb3c0000acfc000000200000bc310000b4500000c0b00000c774"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write sub string with charset`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeString("동해물과 백두산이", 3, 7, Charsets.UTF_32BE)
        sink.flush()

        val readString = data.readByteString()
        log.debug { "Read string=$readString" }

        readString shouldBeEqualTo "과 백두".encode(Charsets.UTF_32BE)
        readString.hex() shouldBeEqualTo "0000acfc000000200000bc310000b450"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write utf8 sub string with charset`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeString("동해물과 백두산이", 3, 7, Charsets.UTF_8)
        sink.flush()

        val readString = data.readByteString()
        log.debug { "Read string=$readString" }

        readString.utf8() shouldBeEqualTo "과 백두"
        readString.hex() shouldBeEqualTo "eab3bc20ebb0b1eb9190"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write all`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        val source = bufferOf("동해물과 백두산이")
        sink.writeAll(source) shouldBeEqualTo 25L
        source.size shouldBeEqualTo 0L
        sink.flush()

        data.readUtf8() shouldBeEqualTo "동해물과 백두산이"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write source`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        val source = bufferOf("abcdef")
        sink.write(source, 4)
        sink.flush()

        data.readUtf8() shouldBeEqualTo "abcd"
        source.readUtf8() shouldBeEqualTo "ef"
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write source propagates Eof`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        // NOTE: Buffer 면 안되고, Source 이어야 EOFException 이 발생한다.
        val source: Source = bufferOf("abcd").asSource()

        assertFailsWith<EOFException> {
            sink.write(source, 8L)
        }

        // 어쨌든 source 의 정보는 쓰여진다.
        sink.flush()
        data.readUtf8() shouldBeEqualTo "abcd"
    }


    @ParameterizedTest
    @MethodSource("factories")
    fun `close emits bufferred bytes`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        sink.writeByte('a'.code)
        sink.close()
        data.readByte().toInt() shouldBeEqualTo 'a'.code
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `output stream bounds`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        val out = sink.outputStream()

        assertFailsWith<ArrayIndexOutOfBoundsException> {
            out.write(ByteArray(100), 50, 51)
        }
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `long decimal string`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        assertLongDecimalString(sink, data, 0L)
        assertLongDecimalString(sink, data, Long.MIN_VALUE)
        assertLongDecimalString(sink, data, Long.MAX_VALUE)

        for (i in 1..20) {
            val value = 10L.toBigInt().pow(i).toLong()
            assertLongDecimalString(sink, data, value - 1)
            assertLongDecimalString(sink, data, value)
        }
    }

    private fun assertLongDecimalString(sink: BufferedSink, data: Buffer, value: Long) {
        sink.writeDecimalLong(value).writeUtf8("zzz").flush()
        val expected = value.toString() + "zzz"

        val actual = data.readUtf8()
        log.debug { "actual=$actual, expected=$expected" }
        actual shouldBeEqualTo expected
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `long hex string`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        assertLongHexString(sink, data, 0L)
        assertLongHexString(sink, data, Long.MIN_VALUE)
        assertLongHexString(sink, data, Long.MAX_VALUE)

        for (i in 0..64) {
            val value = 1L shl i
            assertLongHexString(sink, data, value - 1)
            assertLongHexString(sink, data, value)
        }
    }

    private fun assertLongHexString(sink: BufferedSink, data: Buffer, value: Long) {
        sink.writeHexadecimalUnsignedLong(value).writeUtf8("zzz").flush()
        val expected = String.format("%x", value) + "zzz"
        val actual = data.readUtf8()
        log.debug { "actual=$actual, expected=$expected" }
        actual shouldBeEqualTo expected
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write nio buffer`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        val expected = Fakers.randomString(512)
        val nioByteBuffer = ByteBuffer.allocate(expected.length)
        nioByteBuffer.put(expected.toUtf8Bytes())
        nioByteBuffer.flip() // Prepare the buffer for reading

        val byteCount = sink.write(nioByteBuffer)
        byteCount shouldBeEqualTo expected.length
        nioByteBuffer.position() shouldBeEqualTo expected.length
        nioByteBuffer.limit() shouldBeEqualTo expected.length

        sink.flush()
        data.readUtf8() shouldBeEqualTo expected
    }

    @ParameterizedTest
    @MethodSource("factories")
    fun `write large nio buffer writes all data`(factory: Factory) {
        val data = Buffer()
        val sink = getSink(factory, data)

        val expected = Fakers.randomString(SEGMENT_SIZE * 3)
        val nioByteBuffer = ByteBuffer.allocateDirect(expected.length)
        nioByteBuffer.put(expected.toUtf8Bytes())
        nioByteBuffer.flip() // Prepare the buffer for reading

        val byteCount = sink.write(nioByteBuffer)
        byteCount shouldBeEqualTo expected.length
        nioByteBuffer.position() shouldBeEqualTo expected.length
        nioByteBuffer.limit() shouldBeEqualTo expected.length

        sink.flush()
        data.readUtf8() shouldBeEqualTo expected
    }
}
