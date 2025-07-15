package io.bluetape4k.okio.base64

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import okio.ByteString.Companion.toByteString
import okio.Source

class OkioBase64SourceTest: AbstractBase64SourceTest() {

    companion object: KLogging()

    override fun createSource(delegate: Source): Source = delegate.asBase64Source()

    override fun String.toBase64String(): String = toUtf8Bytes().toByteString().base64()

}
