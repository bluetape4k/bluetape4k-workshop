package io.bluetape4k.workshop.commerce.shared.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

class CommerceIntegrationContractsTest {

    @Test
    fun `payload digest preserves provider lowercase SHA-256 wire contract`() {
        listOf(
            "",
            "한글과 emoji🙂",
            "{\"tenantId\":\"tenant-a\",\"quantity\":\"2.5\"}",
        ).forEach { payload ->
            PayloadDigest.of(payload) shouldBeEqualTo jdkDigest(payload)
        }
    }

    @Test
    fun `payload digest rejects non-canonical values`() {
        val payload = "payload"
        val digest = PayloadDigest.of(payload)

        PayloadDigest.matches(payload, digest) shouldBeEqualTo true
        PayloadDigest.matches(payload, digest.uppercase()) shouldBeEqualTo false
        PayloadDigest.matches(payload, "zz${digest.drop(2)}") shouldBeEqualTo false
        PayloadDigest.matches(payload, digest.dropLast(2)) shouldBeEqualTo false
        PayloadDigest.matches("other", digest) shouldBeEqualTo false
    }

    @Test
    fun `aggregate partition key preserves tenant aggregate id order`() {
        AggregatePartitionKey.of("tenant-a", "Invoice", "invoice-7") shouldBeEqualTo
            "tenant-a|Invoice|invoice-7"
    }

    private fun jdkDigest(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))
}
