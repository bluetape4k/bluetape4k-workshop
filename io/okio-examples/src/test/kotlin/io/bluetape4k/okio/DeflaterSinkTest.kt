package io.bluetape4k.okio

import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.io.okio.byteStringOf
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import okio.Buffer
import okio.Deflater
import okio.DeflaterSink
import okio.IOException
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.zip.InflaterInputStream
import kotlin.random.Random
import kotlin.test.assertFailsWith

class DeflaterSinkTest: AbstractOkioTest() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 3
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `deflate with close`() {
        val original = Fakers.randomString(8192)
        val data = bufferOf(original)

        val sink = Buffer()
        val deflaterSink = DeflaterSink(sink, Deflater())
        deflaterSink.write(data, data.size)  // data 를 읽어서 deflaterSink에 쓴다
        deflaterSink.close()  // deflaterSink를 닫는다

        val inflated = inflate(sink)
        inflated.readUtf8() shouldBeEqualTo original
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `deflate with sync flush`() {
        val original = Fakers.randomString(8192)
        val data = bufferOf(original)

        val sink = Buffer()
        val deflaterSink = DeflaterSink(sink, Deflater())
        deflaterSink.write(data, data.size)
        deflaterSink.flush()
        // deflaterSink.close()  // close 하지 않고, flush 만으로도 충분하다

        val inflated = inflate(sink)
        inflated.readUtf8() shouldBeEqualTo original
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `deflate well compressed`() {
        val original = "a".repeat(1024 * 1024)  // 1MB of 'a'
        val data = bufferOf(original)

        val sink = Buffer()
        val deflaterSink = DeflaterSink(sink, Deflater())
        deflaterSink.write(data, data.size)
        deflaterSink.close()

        val inflated = inflate(sink)
        inflated.readUtf8() shouldBeEqualTo original
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `deflate poorly compressed`() {
        val original = byteStringOf(Random.nextBytes(1024 * 1024))  // 1MB of random bytes
        val data = bufferOf(original)

        val sink = Buffer()
        val deflaterSink = DeflaterSink(sink, Deflater(Deflater.BEST_SPEED))
        deflaterSink.write(data, data.size)
        deflaterSink.close()

        val inflated = inflate(sink)
        inflated.readByteString() shouldBeEqualTo original
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `multiple segments without compression`() {
        val buffer = Buffer()
        val deflater = Deflater(Deflater.NO_COMPRESSION)

        val deflaterSink = DeflaterSink(buffer, deflater)
        val byteCount = SEGMENT_SIZE * 4
        deflaterSink.write(bufferOf("a".repeat(byteCount)), byteCount.toLong())
        deflaterSink.close()

        val inflated = inflate(buffer)
        inflated.readUtf8(byteCount.toLong()) shouldBeEqualTo "a".repeat(byteCount)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `deflate into non empty sink`() {
        val original = Fakers.randomString(8192)

        repeat(SEGMENT_SIZE) {
            val data = bufferOf(original)
            val sink = bufferOf("a".repeat(it))

            val deflaterSink = DeflaterSink(sink, Deflater())
            deflaterSink.write(data, data.size)  // data 를 읽어서 deflaterSink에 쓴다
            deflaterSink.close()  // deflaterSink를 닫는다

            // 기존 정보를 skip하고, deflaterSink에 쓴다
            sink.skip(it.toLong())
            val inflated = inflate(sink)
            inflated.readUtf8() shouldBeEqualTo original
        }
    }

    /**
     * This test deflates a single segment of without compression because that's
     * the easiest way to force close() to emit a large amount of data to the
     * underlying sink.
     */
    @Test
    fun `close with exception when writing and closing`() {
        val mockSink = MockSink()
        mockSink.scheduleThrow(0, IOException("first"))
        mockSink.scheduleThrow(1, IOException("second"))

        val deflater = Deflater(Deflater.NO_COMPRESSION)
        val deflaterSink = DeflaterSink(mockSink, deflater)
        // data 를 읽어서 deflaterSink에 쓴다
        deflaterSink.write(bufferOf("a".repeat(SEGMENT_SIZE)), SEGMENT_SIZE.toLong())

        assertFailsWith<IOException> {
            deflaterSink.close()
        }

        mockSink.assertLogContains("close()")
    }

    /**
     * 이 테스트는 Deflater에서 NullPointerException을 IOException으로 다시 던지는지 확인합니다.
     */
    @Test
    fun `rethrow null pointer as IOException`() {
        val deflater = Deflater()
        // end to cause a NullPointerException
        deflater.end()

        val data = bufferOf(Fakers.randomString())
        val defaterSink = DeflaterSink(data, deflater)

        assertFailsWith<IOException> {
            defaterSink.write(data, data.size)
        }.cause shouldBeInstanceOf NullPointerException::class
    }

    /**
     * Uses streaming decompression to inflate `deflated`.
     * The input must either be finish
     */
    private fun inflate(deflated: Buffer): Buffer {
        val deflatedIn = deflated.inputStream()
        val inflater = okio.Inflater()
        val inflatedIn = InflaterInputStream(deflatedIn, inflater)

        val result = Buffer()
        val buffer = ByteArray(8192)

        while (!inflater.needsInput() || deflated.size > 0 || inflatedIn.available() > 0) {
            // while (inflatedIn.available() > 0) {  // 이 것만으로는 부족하다
            runCatching {
                inflatedIn.read(buffer, 0, buffer.size)
            }.onSuccess { count ->
                if (count != -1) {
                    result.write(buffer, 0, count)
                }
            }.onFailure { ex ->
                return result
            }
        }
        return result
    }
}
