package io.bluetape4k.okio.jasypt

import io.bluetape4k.crypto.encrypt.Encryptor
import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import okio.Buffer
import okio.ForwardingSource
import okio.Source

open class DecryptSource(
    delegate: Source,
    private val encryptor: Encryptor,
): ForwardingSource(delegate) {

    companion object: KLogging()

    override fun read(sink: Buffer, byteCount: Long): Long {
        // byteCount.requireEquals(sink.size, "byteCount")

        // Jasypt 는 Cipher랑 달리 한번에 모두 읽어야 한다.
        val sourceBuffer = Buffer()
        super.read(sourceBuffer, Long.MAX_VALUE)

        val encryptedBytes = sourceBuffer.readByteArray()
        if (encryptedBytes.isEmpty()) {
            return -1 // End of stream
        }

        val decryptedBytes = encryptor.decrypt(encryptedBytes)
        sink.write(bufferOf(decryptedBytes), decryptedBytes.size.toLong())

        log.debug { "Decrypting: encrypted=${encryptedBytes.size} bytes, decrypted: ${decryptedBytes.size} bytes." }

        return decryptedBytes.size.toLong()
    }
}

fun Source.asDecryptSource(encryptor: Encryptor): DecryptSource =
    DecryptSource(this, encryptor)
