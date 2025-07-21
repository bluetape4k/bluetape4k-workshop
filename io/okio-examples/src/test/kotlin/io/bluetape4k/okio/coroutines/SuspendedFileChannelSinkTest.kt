package io.bluetape4k.okio.coroutines

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.okio.AbstractOkioTest
import okio.Buffer
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempFile

class SuspendedFileChannelSinkTest: AbstractOkioTest() {

    companion object: KLoggingChannel() {
        private const val REPEAT_SIZE = 5
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `write and read back data`() = runSuspendIO {
        val tempFile = createTempFile()
        val channel = AsynchronousFileChannel.open(
            tempFile,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        val message = faker.lorem().paragraph(10).repeat(100)
        val sink: SuspendedSink = channel.asSuspendedSink()
        val buffer = Buffer().writeUtf8(message)

        sink.write(buffer, buffer.size)
        sink.flush()
        sink.close()

        val readChannel = AsynchronousFileChannel.open(
            tempFile,
            StandardOpenOption.READ
        )

        val readBuffer = ByteBuffer.allocate(readChannel.size().toInt())
        readChannel.read(readBuffer, 0).get()
        readBuffer.flip()
        val result = ByteArray(readBuffer.remaining())
//        result.toString(Charsets.UTF_8) shouldBeEqualTo message
        readBuffer.get(result)
        String(result).trim { it <= ' ' } shouldBeEqualTo message

        readChannel.close()
        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `write after close throws`() = runSuspendIO {
        val tempFile = createTempFile()
        val channel = AsynchronousFileChannel.open(
            tempFile,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        val sink: SuspendedSink = channel.asSuspendedSink()
        sink.close()

        val buffer = Buffer().writeUtf8("fail")
        kotlin.test.assertFailsWith<IllegalStateException> {
            sink.write(buffer, buffer.size)
        }

        Files.deleteIfExists(tempFile)
    }
}
