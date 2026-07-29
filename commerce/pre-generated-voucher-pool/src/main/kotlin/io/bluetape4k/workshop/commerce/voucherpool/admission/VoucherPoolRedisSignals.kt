@file:Suppress("MatchingDeclarationName")

package io.bluetape4k.workshop.commerce.voucherpool.admission

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** advisory Redis state를 위한 opaque하고 operation-separated된 key를 생성합니다. */
internal class VoucherPoolRedisSignalKeyFactory(
    private val version: Int = 1,
) {
    init {
        require(version > 0) { "Redis signal key version must be positive" }
    }

    fun admissionKey(namespace: AdmissionNamespace, principalDigest: ByteArray): String {
        require(principalDigest.isNotEmpty()) { "principalDigest must not be empty" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(field(DOMAIN))
        digest.update(field(namespace.name))
        digest.update(field(principalDigest))
        return "v$version:${digest.digest().toHexString()}"
    }

    private fun field(value: String): ByteArray = field(value.toByteArray(StandardCharsets.UTF_8))

    private fun field(value: ByteArray): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES + value.size).putInt(value.size).put(value).array()

    private companion object {
        const val DOMAIN = "voucher-pool-redis-signal"
    }
}
