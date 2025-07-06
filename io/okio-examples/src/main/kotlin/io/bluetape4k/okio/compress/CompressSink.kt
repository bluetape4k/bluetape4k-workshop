package io.bluetape4k.okio.compress

import io.bluetape4k.io.compressor.Compressor
import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.trace
import okio.Buffer
import okio.ForwardingSink
import okio.Sink

/**
 * 데이터를 압축하여 [Sink]에 쓰는 [Sink] 구현체.
 *
 * @see DecompressSource
 */
class CompressSink(
    delegate: okio.Sink,
    private val comressor: Compressor,
): ForwardingSink(delegate) {

    companion object: KLogging()

    override fun write(source: Buffer, byteCount: Long) {
        // byteCount.requirePositiveNumber("byteCount")

        // 압축은 `source`의 모든 데이터를 압축해야 함
        val bytesToRead = source.size
        val plainBytes = source.readByteArray(bytesToRead)
        log.trace { "Compressing: ${plainBytes.size} bytes" }

        // 압축
        val compressed = comressor.compress(plainBytes)
        super.write(bufferOf(compressed), compressed.size.toLong())
    }
}

fun okio.Sink.asCompressSink(compressor: Compressor): CompressSink {
    return CompressSink(this, compressor)
}
