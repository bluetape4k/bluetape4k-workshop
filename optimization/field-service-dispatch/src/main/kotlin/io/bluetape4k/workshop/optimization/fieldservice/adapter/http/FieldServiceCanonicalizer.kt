package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigest
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigestMatch
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEvents
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
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

/** Event digest 전에 JSON 입력을 닫힌 canonical 표현으로 정규화합니다. */
class FieldServiceCanonicalizer {
    private val mapper: JsonMapper = JsonMapper.builder(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(FieldServiceLimits.MAX_JSON_DEPTH)
                    .maxStringLength(FieldServiceLimits.MAX_STRING_LENGTH)
                    .maxNameLength(FieldServiceLimits.MAX_KEY_LENGTH)
                    .maxDocumentLength(FieldServiceLimits.MAX_BODY_BYTES.toLong())
                    .build(),
            )
            .build(),
    ).addModule(kotlinModule())
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun canonicalBytes(body: ByteArray): ByteArray {
        if (body.isEmpty() || body.size > FieldServiceLimits.MAX_BODY_BYTES) {
            throw InvalidFieldServiceInput("JSON body must be 1..${FieldServiceLimits.MAX_BODY_BYTES} bytes")
        }
        val node = try {
            mapper.readTree(body)
        } catch (failure: Exception) {
            throw InvalidFieldServiceInput("invalid canonical JSON", failure)
        } ?: throw InvalidFieldServiceInput("JSON body must not be empty")
        return canonicalNode(node, depth = 0).toByteArray(UTF_8)
    }

    fun digest(body: ByteArray): EventDigest =
        EventDigest(
            MessageDigest.getInstance("SHA-256")
                .digest(canonicalBytes(body))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) },
        )

    fun compareStoredDigest(stored: EventDigest, incoming: EventDigest): EventDigestMatch =
        FieldServiceEvents.compare(stored, incoming)

    private fun canonicalNode(node: JsonNode, depth: Int): String {
        if (depth > FieldServiceLimits.MAX_JSON_DEPTH) {
            throw InvalidFieldServiceInput("JSON depth exceeds ${FieldServiceLimits.MAX_JSON_DEPTH}")
        }
        return when {
            node.isObject -> node.properties().asSequence()
                .sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { property ->
                    "${quote(property.key)}:${canonicalNode(property.value, depth + 1)}"
                }
            node.isArray -> node.iterator().asSequence()
                .joinToString(prefix = "[", postfix = "]") { child -> canonicalNode(child, depth + 1) }
            node.isString -> quote(node.stringValue())
            node.isNumber -> canonicalNumber(node)
            node.isBoolean || node.isNull -> node.toString()
            else -> throw InvalidFieldServiceInput("unsupported JSON value")
        }
    }

    private fun canonicalNumber(node: JsonNode): String {
        val decimal = try {
            node.decimalValue()
        } catch (failure: Exception) {
            throw InvalidFieldServiceInput("JSON number must be finite", failure)
        }
        val normalized = decimal.stripTrailingZeros()
        return if (normalized.compareTo(BigDecimal.ZERO) == 0) "0" else normalized.toPlainString()
    }

    private fun quote(value: String): String = mapper.writeValueAsString(value)
}
