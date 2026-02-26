package io.bluetape4k.okio.compress

import io.bluetape4k.io.compressor.Compressor
import io.bluetape4k.io.compressor.Compressors
import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.io.okio.byteStringOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.okio.AbstractOkioTest
import okio.Buffer
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random

class CompressableSinkSourceTest: AbstractOkioTest() {

    companion object: KLogging()

    private fun compressors() = listOf(
        Compressors.BZip2,
        Compressors.Deflate,
        Compressors.GZip,
        Compressors.LZ4,
        Compressors.Snappy,
        Compressors.Zstd
    )

    @ParameterizedTest(name = "compressor={0}")
    @MethodSource("compressors")
    fun `compress a random sentence`(compressor: Compressor) {
        val original = faker.lorem().sentence()
        val data = bufferOf(original)

        val sink = Buffer()
        val compressSink = sink.asCompressSink(compressor)
        // Write data to compressable sink
        compressSink.write(data, data.size)
        compressSink.flush()

        val source = Buffer()
        val decompressableSource = sink.asDecompressSource(compressor)
        decompressableSource.read(source, sink.size)

        // Verify the decompressed data matches the original
        source.readUtf8() shouldBeEqualTo original
    }

    @ParameterizedTest(name = "compressor={0}")
    @MethodSource("compressors")
    fun `compress a large string`(compressor: Compressor) {
        val original = faker.lorem().paragraph().repeat(1024)
        val data = bufferOf(original)

        val sink = Buffer()
        val compressSink = sink.asCompressSink(compressor)
        // Write data to compressable sink
        compressSink.write(data, data.size)
        compressSink.flush()

        val source = Buffer()
        val decompressableSource = sink.asDecompressSource(compressor)
        decompressableSource.read(source, sink.size)

        // Verify the decompressed data matches the original
        source.readUtf8() shouldBeEqualTo original
    }

    @ParameterizedTest(name = "compressor={0}")
    @MethodSource("compressors")
    fun `compress a byte string`(compressor: Compressor) {
        val original = byteStringOf(Random.nextBytes(1024 * 1024)) // 1MB of random bytes
        val data = bufferOf(original)

        val sink = Buffer()
        val compressSink = sink.asCompressSink(compressor)

        // Write data to compressable sink
        compressSink.write(data, data.size)
        compressSink.flush()

        val source = Buffer()
        val decompressableSource = sink.asDecompressSource(compressor)
        decompressableSource.read(source, sink.size)

        // Verify the decompressed data matches the original
        source.readByteString() shouldBeEqualTo original
    }
}
