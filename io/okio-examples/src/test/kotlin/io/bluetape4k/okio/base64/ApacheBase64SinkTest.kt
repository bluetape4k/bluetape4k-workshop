package io.bluetape4k.okio.base64

import io.bluetape4k.codec.encodeBase64String
import io.bluetape4k.logging.KLogging
import okio.Sink

class ApacheBase64SinkTest: AbstractBase64SinkTest() {

    companion object: KLogging()

    override fun createSink(delegate: Sink): Sink = delegate.asApacheBase64Sink()

    override fun ByteArray.getEncodedString(): String = encodeBase64String()
}
