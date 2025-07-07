package io.bluetape4k.okio.jasypt

import io.bluetape4k.crypto.encrypt.Encryptor
import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import okio.Buffer
import okio.ForwardingSink
import okio.Sink

open class EncryptSink(
    delegate: Sink,
    private val encryptor: Encryptor,
): ForwardingSink(delegate) {

    companion object: KLogging()

    override fun write(source: Buffer, byteCount: Long) {
        // Encryptor는 한 번에 모든 데이터를 암호화해야 함
        // require(byteCount >= source.size) { "byteCount must be greater than or equal to source size" }

        // 요청한 바이트 수(또는 가능한 모든 바이트) 반환
        val plainBytes = source.readByteArray()

        // 암호화
        val encryptedBytes = encryptor.encrypt(plainBytes)
        log.debug { "Encrypting: plain=${plainBytes.size} bytes, encrypted: ${encryptedBytes.size} bytes." }
        super.write(bufferOf(encryptedBytes), encryptedBytes.size.toLong())
    }
}

fun Sink.asEncryptSink(encryptor: Encryptor): EncryptSink =
    EncryptSink(this, encryptor)
