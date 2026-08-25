package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanProposal
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.text.Normalizer

internal class WarehouseAllocationCodec {
    private val mapper: JsonMapper = JsonMapper.builder(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(12)
                    .maxStringLength(256 * 1024)
                    .maxNameLength(200)
                    .maxDocumentLength(256L * 1024L)
                    .build(),
            ).build(),
    ).addModule(kotlinModule())
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun encode(value: Any): String = canonicalNode(mapper.valueToTree(value), 0)

    fun <T> decode(value: String, type: Class<T>): T = mapper.readValue(value, type)

    fun decodePlan(value: String): PlanProposal = mapper.readValue(value, PlanProposal::class.java)

    fun canonicalBytes(body: ByteArray): ByteArray {
        require(body.isNotEmpty() && body.size <= 256 * 1024) { "body exceeds 256KiB" }
        val node = mapper.readTree(body) ?: error("empty JSON")
        return canonicalNode(node, 0).toByteArray(UTF_8)
    }

    fun digest(value: Any): String = sha256(encode(value).toByteArray(UTF_8))

    fun digestBytes(body: ByteArray): String = sha256(canonicalBytes(body))

    private fun canonicalNode(node: JsonNode, depth: Int): String {
        require(depth <= 12) { "JSON depth exceeds 12" }
        return when {
            node.isObject -> node.properties().asSequence().sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { "${quote(it.key)}:${canonicalNode(it.value, depth + 1)}" }
            node.isArray -> node.iterator().asSequence().joinToString(prefix = "[", postfix = "]") { canonicalNode(it, depth + 1) }
            node.isTextual -> quote(Normalizer.normalize(node.textValue(), Normalizer.Form.NFC))
            node.isNumber -> {
                val decimal = node.decimalValue()
                val normalized = decimal.stripTrailingZeros()
                if (normalized.compareTo(BigDecimal.ZERO) == 0) "0" else normalized.toPlainString()
            }
            node.isBoolean || node.isNull -> node.toString()
            else -> error("unsupported JSON value")
        }
    }

    private fun quote(value: String): String = mapper.writeValueAsString(value)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
