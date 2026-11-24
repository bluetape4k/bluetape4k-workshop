package io.bluetape4k.workshop.commerce.shared.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.io.digest.Digesters
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class DigestMigrationParityTest {

    @Test
    fun `batch checkpoint and chunk digest vectors preserve ordered bytes`() {
        val initialCheckpoint = jdkSha256(
            "voucher-pool-batch-final-checkpoint-v1".toByteArray(Charsets.UTF_8),
            ByteArray(32) { 1 },
            longBytes(1),
        )
        val advancedCheckpoint = jdkSha256(
            "voucher-pool-batch-checkpoint-step-v1".toByteArray(Charsets.UTF_8),
            ByteArray(32) { 1 },
            longBytes(1),
            ByteArray(32) { 2 },
        )
        val requestDigest = jdkSha256(
            "voucher-pool-batch-chunk-fingerprint-v1".toByteArray(Charsets.UTF_8),
            longBytes(0),
            longBytes(0),
            ByteArray(32) { 1 },
        )
        val initialCheckpointWithDigester = digesterSha256(
            "voucher-pool-batch-final-checkpoint-v1".toByteArray(Charsets.UTF_8),
            ByteArray(32) { 1 },
            longBytes(1),
        )
        val advancedCheckpointWithDigester = digesterSha256(
            "voucher-pool-batch-checkpoint-step-v1".toByteArray(Charsets.UTF_8),
            ByteArray(32) { 1 },
            longBytes(1),
            ByteArray(32) { 2 },
        )
        val requestDigestWithDigester = digesterSha256(
            "voucher-pool-batch-chunk-fingerprint-v1".toByteArray(Charsets.UTF_8),
            longBytes(0),
            longBytes(0),
            ByteArray(32) { 1 },
        )

        initialCheckpoint shouldBeEqualTo "b47b6ce47012921f5f56e6d2832f664abf03fc4ce42805cb0b677d0d5a34c272"
        advancedCheckpoint shouldBeEqualTo "6e37655c2e1953870a7aeef188b2af64c4aeae34b5bf7d4034ee556913279586"
        requestDigest shouldBeEqualTo "230b8a400441dff728447a25095201d8a9dd5e3dc5e6e884a6a31171a96a349f"
        initialCheckpointWithDigester shouldBeEqualTo initialCheckpoint
        advancedCheckpointWithDigester shouldBeEqualTo advancedCheckpoint
        requestDigestWithDigester shouldBeEqualTo requestDigest
    }

    private fun longBytes(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()

    private fun jdkSha256(vararg fields: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach(digest::update)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun digesterSha256(vararg fields: ByteArray): String =
        Digesters.SHA256.newSession().apply {
            fields.forEach(::update)
        }.digestHex()
}
