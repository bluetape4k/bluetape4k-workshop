package io.bluetape4k.okio.cipher

import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.junit5.tempfolder.TempFolder
import io.bluetape4k.junit5.tempfolder.TempFolderTest
import io.bluetape4k.okio.channels.FileChannelSink
import io.bluetape4k.okio.channels.FileChannelSource
import io.bluetape4k.support.toUtf8Bytes
import okio.Buffer
import okio.Timeout
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.*

@TempFolderTest
class CipherSinkSourceTest: AbstractCipherTest() {

    companion object {
        private const val REPEAT_SIZE = 5

        private val r = EnumSet.of(StandardOpenOption.READ)
        private val w = EnumSet.of(StandardOpenOption.WRITE)
        private val append = EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND)
    }

    private lateinit var tempFolder: TempFolder

    @BeforeAll
    fun beforeAll(tempFolder: TempFolder) {
        this.tempFolder = tempFolder
    }

    @ParameterizedTest(name = "cipher copy with length={0}")
    @ValueSource(ints = [0, 10, 8192, 16384])
    fun `cipher sink and read all`(size: Int) {
        val buffer = Buffer()
        val expected = Fakers.randomString(size)

        val cipherSink = CipherSink(buffer, encryptCipher)
        val input = bufferOf(expected.toUtf8Bytes())

        cipherSink.write(input, input.size)
        cipherSink.flush()

        val cipherSource = CipherSource(buffer, decryptCipher)
        val output = Buffer()

        cipherSource.readAll(output)
        output.readUtf8() shouldBeEqualTo expected
    }

    @ParameterizedTest(name = "cipher copy with length={0}")
    @ValueSource(ints = [0, 10, 8192, 16384])
    fun `cipher file`(size: Int) {
        val expected = Fakers.randomString(size)
        val path = tempFolder.createFile().toPath()

        FileChannelSink(FileChannel.open(path, w), Timeout.NONE).use { fileSink ->
            val cipherSink = CipherSink(fileSink, encryptCipher)
            val input = bufferOf(expected)

            cipherSink.write(input, input.size)
            cipherSink.flush()
        }

        FileChannelSource(FileChannel.open(path, r), Timeout.NONE).use { fileSource ->
            val cipherSource = CipherSource(fileSource, decryptCipher)
            val output = Buffer()

            cipherSource.readAll(output)
            output.readUtf8() shouldBeEqualTo expected
        }
    }
}
