package io.bluetape4k.workshop.optimization.shiftcoverage.adapter

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageEventType
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageLimits
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class ShiftCoverageCallbackEnvelope(val eventType: ShiftCoverageEventType)

/** callback raw JSON을 닫힌 envelope로 검증하고 HMAC 입력용 canonical bytes로 만듭니다. */
class ShiftCoverageCallbackCanonicalizer {
    private val mapper = JsonMapper.builder(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(ShiftCoverageLimits.MAX_JSON_DEPTH)
                    .maxStringLength(ShiftCoverageLimits.MAX_STRING_LENGTH)
                    .maxNameLength(ShiftCoverageLimits.MAX_STRING_LENGTH)
                    .maxDocumentLength(ShiftCoverageLimits.MAX_BODY_BYTES.toLong())
                    .build(),
            )
            .build(),
    ).enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun parse(body: ByteArray): ShiftCoverageCallbackEnvelope {
        val node = read(body)
        requireKeys(node, setOf("event"))
        val event = node["event"]?.takeIf { it.isString }?.stringValue()
            ?: throw InvalidShiftCoverageInput("callback event must be a string")
        val type = ShiftCoverageEventType.entries.firstOrNull { it.wireName == event }
            ?: throw InvalidShiftCoverageInput("unknown callback event")
        return ShiftCoverageCallbackEnvelope(type)
    }

    fun canonicalBytes(body: ByteArray): ByteArray {
        val node = read(body)
        requireKeys(node, setOf("event"))
        return canonical(node, 0).toByteArray(UTF_8)
    }

    private fun read(body: ByteArray): JsonNode {
        if (body.isEmpty() || body.size > ShiftCoverageLimits.MAX_BODY_BYTES) {
            throw InvalidShiftCoverageInput("callback JSON body is outside the allowed size")
        }
        return try {
            mapper.readTree(body) ?: throw InvalidShiftCoverageInput("callback JSON body must not be empty")
        } catch (failure: InvalidShiftCoverageInput) {
            throw failure
        } catch (failure: Exception) {
            throw InvalidShiftCoverageInput("invalid callback JSON", failure)
        }
    }

    private fun requireKeys(node: JsonNode, allowed: Set<String>) {
        if (!node.isObject) throw InvalidShiftCoverageInput("callback envelope must be an object")
        val actual = node.properties().asSequence().map { it.key }.toSet()
        if (actual != allowed) throw InvalidShiftCoverageInput("callback envelope fields are not closed")
    }

    private fun canonical(node: JsonNode, depth: Int): String {
        if (depth > ShiftCoverageLimits.MAX_JSON_DEPTH) throw InvalidShiftCoverageInput("callback JSON depth is too deep")
        return when {
            node.isObject -> node.properties().asSequence().sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { "${quote(it.key)}:${canonical(it.value, depth + 1)}" }
            node.isArray -> node.iterator().asSequence().joinToString(prefix = "[", postfix = "]") { canonical(it, depth + 1) }
            node.isString -> quote(node.stringValue())
            node.isNumber -> canonicalNumber(node)
            node.isBoolean || node.isNull -> node.toString()
            else -> throw InvalidShiftCoverageInput("unsupported callback JSON value")
        }
    }

    private fun canonicalNumber(node: JsonNode): String {
        val decimal = try { node.decimalValue() } catch (failure: Exception) {
            throw InvalidShiftCoverageInput("callback JSON number is invalid", failure)
        }
        val normalized = decimal.stripTrailingZeros()
        return if (normalized.compareTo(BigDecimal.ZERO) == 0) "0" else normalized.toPlainString()
    }

    private fun quote(value: String): String = mapper.writeValueAsString(value)
}
