package io.bluetape4k.okio

import io.bluetape4k.io.okio.bufferOf
import okio.Buffer
import kotlin.random.Random

enum class BufferFactory {

    EMPTY {
        override fun newBuffer(): Buffer = Buffer()
    },
    SMALL_BUFFER {
        override fun newBuffer(): Buffer = bufferOf("abcde")
    },

    SMALL_SEGMENTED_BUFFER {
        override fun newBuffer(): Buffer = TestUtil.bufferWithSegments("abc", "defg", "hijkl")
    },

    LARGE_BUFFER {
        override fun newBuffer(): Buffer {
            val largeByteArray = ByteArray(512 * 1024)
            Random.nextBytes(largeByteArray)
            return Buffer().write(largeByteArray)
        }
    },

    LARGE_BUFFER_WITH_RANDOM_LAYOUT {
        override fun newBuffer(): Buffer {
            val largeByteArray = ByteArray(512 * 1024)
            Random.nextBytes(largeByteArray)
            return TestUtil.bufferWithRandomSegmentLayout(largeByteArray)
        }
    };

    abstract fun newBuffer(): Buffer
}
