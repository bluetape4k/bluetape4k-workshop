package io.bluetape4k.okio

import io.bluetape4k.junit5.tempfolder.TempFolder
import io.bluetape4k.junit5.tempfolder.TempFolderTest
import io.bluetape4k.logging.KLogging
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import okio.sink
import org.junit.jupiter.api.Test

@TempFolderTest
class TeeSinkTest {

    companion object: KLogging()

    @Test
    fun `정보를 2개의 sink예 씁니다`(tempFolder: TempFolder) {
        val a = System.out.sink()
        val b = tempFolder.createFile().sink()

        TeeSink(a, b).buffer().use { teeSink ->
            teeSink.writeUtf8("hello\n")
            teeSink.flush()
            teeSink.writeUtf8("world!")
        }
    }

    class TeeSink(
        private val sinkA: Sink,
        private val sinkB: Sink,
    ): Sink {

        private val timeout = Timeout()

        override fun write(source: Buffer, byteCount: Long) {
            // sink 에 쓰면 source 가 mutate 되므로 이를 우회합니다.
            sinkA.timeout().intersectWith(timeout) {
                val buffer = Buffer()
                source.copyTo(buffer, byteCount = byteCount)
                sinkA.write(buffer, byteCount)
            }

            sinkB.timeout().intersectWith(timeout) {
                sinkB.write(source, byteCount)
            }
        }

        override fun flush() {
            sinkA.flush()
            sinkB.flush()
        }

        override fun close() {
            val result1 = runCatching { sinkA.close() }
            val result2 = runCatching { sinkB.close() }

            if (result1.isFailure || result2.isFailure) {
                val tA = result1.exceptionOrNull()
                val tB = result2.exceptionOrNull()
                if (tA != null && tB != null) {
                    tA.addSuppressed(tB)
                }
                throw tA ?: tB ?: IllegalStateException("Both sinks failed to close")
            }
        }

        override fun timeout() = sinkA.timeout()
    }
}
