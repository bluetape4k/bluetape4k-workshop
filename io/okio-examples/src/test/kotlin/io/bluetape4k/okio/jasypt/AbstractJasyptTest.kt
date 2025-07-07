package io.bluetape4k.okio.jasypt

import io.bluetape4k.crypto.encrypt.Encryptor
import io.bluetape4k.crypto.encrypt.Encryptors
import io.bluetape4k.io.compressor.Compressors
import io.bluetape4k.logging.KLogging
import io.bluetape4k.okio.AbstractOkioTest
import net.datafaker.Faker
import java.util.*

abstract class AbstractJasyptTest: AbstractOkioTest() {

    companion object: KLogging() {
        const val REPEAT_SIZE = 5

        @JvmStatic
        val faker = Faker(Locale.getDefault())
    }

    protected fun encryptors(): List<Encryptor> = listOf(
        Encryptors.AES,
        Encryptors.DES,
        Encryptors.TripleDES,
        Encryptors.RC2,
        Encryptors.RC4
    )

    protected fun compressors() = listOf(
        Compressors.BZip2,
        Compressors.Deflate,
        Compressors.GZip,
        Compressors.LZ4,
        Compressors.Snappy,
        Compressors.Zstd
    )
}
