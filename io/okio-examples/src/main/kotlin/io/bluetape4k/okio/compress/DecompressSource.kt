package io.bluetape4k.okio.compress

import io.bluetape4k.io.compressor.Compressor
import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.trace

/**
 * 데이터를 압축 해제하여 [okio.Source]로 읽는 [okio.ForwardingSource] 구현체.
 *
 * @see CompressSink
 */
class DecompressSource(
    delegate: okio.Source,
    private val compressor: Compressor,
): okio.ForwardingSource(delegate) {

    companion object: KLogging()

    /**
     * 압축 해제는 한 번에 모든 데이터를 복원해야 하므로, `byteCount`는 무시됩니다.
     * 대신, 가능한 모든 데이터를 읽고 복원합니다.
     */
    override fun read(sink: okio.Buffer, byteCount: Long): Long {
        val sourceBuffer = okio.Buffer()

        // 압축 복원은 한 번에 모든 데이터를 복원해야 함
        val bytesRead = super.read(sourceBuffer, Long.MAX_VALUE)
        log.trace { "byteCount=$byteCount, sourceBuffer.size=${sourceBuffer.size}" }

        if (bytesRead < 0) {
            return -1 // End of stream
        }

        val decompressed = compressor.decompress(sourceBuffer.readByteArray())
        log.trace { "decompressed bytes: ${decompressed.size}" }
        sink.write(bufferOf(decompressed), decompressed.size.toLong())
        return decompressed.size.toLong()
    }
}

fun okio.Source.asDecompressSource(compressor: Compressor): DecompressSource {
    return DecompressSource(this, compressor)
}
