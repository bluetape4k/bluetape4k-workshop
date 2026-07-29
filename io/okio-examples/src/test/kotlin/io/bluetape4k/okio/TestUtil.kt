package io.bluetape4k.okio

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import okio.Buffer
import io.bluetape4k.assertions.shouldBeEqualTo
import kotlin.random.Random

object TestUtil: KLogging() {

    const val SEGMENT_POOL_MAX_SIZE = 64 * 1024 // 64 KiB
    const val SEGMENT_SIZE = 8192 // 8 KiB

    const val REPLACEMEMT_BYTE: Byte = '?'.code.toByte()
    const val REPLACEMENT_CHARACTER: Char = '\ufffd'
    const val REPLACEMENT_CODE_POINT: Int = REPLACEMENT_CHARACTER.code

    fun assertByteArraysEquals(a: ByteArray, b: ByteArray) {
        a.contentToString() shouldBeEqualTo b.contentToString()
    }

    fun randomBytes(length: Int): ByteArray {
        val randomBytes = ByteArray(length)
        Random.nextBytes(randomBytes)
        return randomBytes
    }

    fun bufferWithSegments(vararg segments: String): Buffer {
        val result = Buffer()
        for (segment in segments) {
            val offsetInSegment = if (segment.length < SEGMENT_SIZE) (SEGMENT_SIZE - segment.length) / 2 else 0
            val buffer = Buffer().apply {
                writeUtf8("_".repeat(offsetInSegment))
                writeUtf8(segment)
                skip(offsetInSegment.toLong())
            }
            log.debug { "buffer=$buffer, buffer.size=${buffer.size}" }
            result.write(buffer.clone(), buffer.size)
        }
        return result
    }

    fun bufferWithRandomSegmentLayout(data: ByteArray): Buffer {
        val result = Buffer()

        // result 에 직접 쓰면 packed segment 가 생깁니다. 대신 다른 buffer 에 쓴 뒤
        // 그 buffer 들을 result 로 씁니다.
        var pos = 0
        var byteCount: Int
        while (pos < data.size) {
            byteCount = SEGMENT_SIZE / 2 + Random.nextInt(SEGMENT_SIZE / 2)
            if (byteCount > data.size - pos) byteCount = data.size - pos
            val offset = Random.nextInt(SEGMENT_SIZE - byteCount)

            val segment = Buffer().apply {
                write(ByteArray(offset))
                write(data, pos, byteCount)
                skip(offset.toLong())
            }

            result.write(segment, byteCount.toLong())
            pos += byteCount
        }
        return result
    }
}
