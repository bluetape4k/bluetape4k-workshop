package io.bluetape4k.okio.base64

import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.okio.AbstractOkioTest
import okio.Buffer
import okio.Source
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.RepeatedTest

abstract class AbstractBase64SourceTest: AbstractOkioTest() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 5
    }

    protected abstract fun createSource(delegate: Source): Source
    protected abstract fun String.toBase64String(): String

    protected fun String.toDecodedSource(): Source {
        val base64String = this.toBase64String()
        return createSource(bufferOf(base64String))
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `read from fixed string`() {
        val content = Fakers.fixedString(32)
        val source = content.toDecodedSource()

        val output = bufferOf(source)
        output.readUtf8() shouldBeEqualTo content
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `read from random long string`() {
        val content = faker.lorem().paragraph().repeat(100)
        val source = content.toDecodedSource()

        val output = bufferOf(source)
        output.readUtf8() shouldBeEqualTo content
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `read partial read`() {
        val content = Fakers.fixedString(32)
        val source = content.toDecodedSource()


        val output = Buffer()
        source.read(output, 5)

        output.readUtf8() shouldBeEqualTo content.substring(0, 5)
    }
}
