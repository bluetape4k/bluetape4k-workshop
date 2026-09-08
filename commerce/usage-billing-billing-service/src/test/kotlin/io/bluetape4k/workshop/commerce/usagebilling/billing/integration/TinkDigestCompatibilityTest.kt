package io.bluetape4k.workshop.commerce.usagebilling.billing.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.tink.digest.TinkDigesters
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

class TinkDigestCompatibilityTest {

    @Test
    fun `Tink SHA-256는 기존 UTF-8 lowercase hex wire 계약을 유지한다`() {
        listOf(
            "",
            "한글과 emoji🙂",
            "{\"tenantId\":\"tenant-a\",\"quantity\":\"2.5\"}",
        ).forEach { payload ->
            TinkDigesters.SHA256.digestHex(payload) shouldBeEqualTo jdkDigestHex(payload)
        }
    }

    @Test
    fun `검증기는 canonical lowercase 64자 digest만 허용한다`() {
        val payload = "한글과 emoji🙂"
        val digest = jdkDigestHex(payload)

        TinkDigesters.SHA256.matchesHex(payload, digest) shouldBeEqualTo true
        TinkDigesters.SHA256.matchesHex(payload, digest.uppercase()) shouldBeEqualTo false
        TinkDigesters.SHA256.matchesHex(payload, "not-hex") shouldBeEqualTo false
        TinkDigesters.SHA256.matchesHex(payload, digest.dropLast(2)) shouldBeEqualTo false
    }

    private fun jdkDigestHex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))
}
