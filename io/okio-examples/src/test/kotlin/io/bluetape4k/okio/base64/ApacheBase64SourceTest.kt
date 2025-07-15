package io.bluetape4k.okio.base64

import io.bluetape4k.support.toUtf8Bytes
import okio.Source
import java.util.*

class ApacheBase64SourceTest: AbstractBase64SourceTest() {

    override fun createSource(delegate: Source): Source = delegate.asBase64Source()

    override fun String.toBase64String(): String = Base64.getUrlEncoder().encodeToString(this.toUtf8Bytes())
}
