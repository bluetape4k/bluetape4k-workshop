package io.bluetape4k.okio.base64

import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.logging.debug
import io.bluetape4k.okio.AbstractOkioTest
import io.bluetape4k.support.toUtf8Bytes
import okio.Buffer
import okio.Sink
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.RepeatedTest

abstract class AbstractBase64SinkTest: AbstractOkioTest() {

    companion object {
        private const val REPEAT_SIZE = 5
    }

    protected abstract fun createSink(delegate: Sink): Sink
    protected abstract fun ByteArray.getEncodedString(): String

    @RepeatedTest(REPEAT_SIZE)
    fun `write fixed string`() {
        val output = Buffer()
        val sink = createSink(output)

        val expectedString = faker.lorem().paragraph()
        log.debug { "Plain string: $expectedString" }

        // Sink에 expectedString을 쓴다
        val source = bufferOf(expectedString)
        sink.write(source, source.size)

        // Base64로 인코딩된 문자열을 읽는다
        val encoded = output.readUtf8()
        log.debug { "Encoded data: $encoded" }

        encoded shouldBeEqualTo expectedString.toUtf8Bytes().getEncodedString()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `write partial string`() {
        val output = Buffer()
        val sink = createSink(output)

        val expectedString = faker.lorem().paragraph()
        log.debug { "Encoded string: $expectedString" }

        // Sink에 expectedString의 일부를 쓴다
        val source = bufferOf(expectedString)
        sink.write(source, 5)

        // Base64로 인코딩된 문자열을 읽는다
        val encoded = output.readUtf8()
        log.debug { "Encoded data: $encoded" }

        encoded shouldBeEqualTo expectedString.toUtf8Bytes().copyOfRange(0, 5).getEncodedString()
    }
}
