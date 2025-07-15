package io.bluetape4k.okio.base64

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8String
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.Source
import java.util.*


/**
 * 데이터를 Apache Commons의 Base64로 인코딩하여 [Source]에 쓰는 [Source] 구현체.
 * NOTE: Apache Commons의 Base64 인코딩은 okio의 Base64 인코딩과 다르다. (특히 한글의 경우)
 *
 * @see ApacheBase64Sink
 */
class ApacheBase64Source(delegate: Source): AbstractBase64Source(delegate) {

    companion object: KLogging()

    override fun decodeBase64Bytes(encodedString: String): ByteString? {
        return Base64.getUrlDecoder().decode(encodedString).toUtf8String().encodeUtf8()
    }
}

fun Source.asApacheBase64Source(): ApacheBase64Source = when (this) {
    is ApacheBase64Source -> this
    else -> ApacheBase64Source(this)
}
