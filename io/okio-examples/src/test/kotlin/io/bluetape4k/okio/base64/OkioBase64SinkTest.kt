package io.bluetape4k.okio.base64

import io.bluetape4k.logging.KLogging
import okio.ByteString
import okio.Sink

class OkioBase64SinkTest: AbstractBase64SinkTest() {

    companion object: KLogging()

    override fun createSink(delegate: Sink): Sink = delegate.asBase64Sink()

    override fun ByteArray.getEncodedString(): String = ByteString.of(*this).base64()
}
