package io.bluetape4k.workshop.optimization.shiftcoverage.adapter

import io.bluetape4k.jackson3.CanonicalJson
import io.bluetape4k.jackson3.CanonicalJsonLimits
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.InvalidShiftCoverageInput
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageEventType
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageLimits
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

data class ShiftCoverageCallbackEnvelope(val eventType: ShiftCoverageEventType)

/** callback raw JSON을 닫힌 envelope로 검증하고 HMAC 입력용 canonical bytes로 만듭니다. */
class ShiftCoverageCallbackCanonicalizer {
    private val mapper = JsonMapper.builder().build()
    private val canonicalJson = CanonicalJson(
        limits = CanonicalJsonLimits(
            maxBodyBytes = ShiftCoverageLimits.MAX_BODY_BYTES,
            maxDepth = ShiftCoverageLimits.MAX_JSON_DEPTH,
            maxStringLength = ShiftCoverageLimits.MAX_STRING_LENGTH,
            maxNameLength = ShiftCoverageLimits.MAX_STRING_LENGTH,
            maxObjectEntries = ShiftCoverageLimits.MAX_BODY_BYTES,
            maxArrayElements = ShiftCoverageLimits.MAX_BODY_BYTES,
            maxOutputBytes = ShiftCoverageLimits.MAX_BODY_BYTES,
        ),
    )

    fun parse(body: ByteArray): ShiftCoverageCallbackEnvelope {
        val node = readCanonical(body).node
        requireKeys(node, setOf("event"))
        val event = node["event"]?.takeIf { it.isString }?.stringValue()
            ?: throw InvalidShiftCoverageInput("callback event must be a string")
        val type = ShiftCoverageEventType.entries.firstOrNull { it.wireName == event }
            ?: throw InvalidShiftCoverageInput("unknown callback event")
        return ShiftCoverageCallbackEnvelope(type)
    }

    fun canonicalBytes(body: ByteArray): ByteArray {
        return readCanonical(body).bytes
    }

    private fun readCanonical(body: ByteArray): Canonicalized {
        if (body.isEmpty() || body.size > ShiftCoverageLimits.MAX_BODY_BYTES) {
            throw InvalidShiftCoverageInput("callback JSON body is outside the allowed size")
        }
        val bytes = try {
            canonicalJson.canonicalBytes(body)
        } catch (failure: InvalidShiftCoverageInput) {
            throw failure
        } catch (failure: Exception) {
            throw InvalidShiftCoverageInput("invalid callback JSON", failure)
        }
        val node = try {
            mapper.readTree(bytes.inputStream()) ?: throw InvalidShiftCoverageInput("callback JSON body must not be empty")
        } catch (failure: InvalidShiftCoverageInput) {
            throw failure
        } catch (failure: Exception) {
            throw InvalidShiftCoverageInput("invalid callback JSON", failure)
        }
        requireKeys(node, setOf("event"))
        return Canonicalized(node, bytes)
    }

    private fun requireKeys(node: JsonNode, allowed: Set<String>) {
        if (!node.isObject) throw InvalidShiftCoverageInput("callback envelope must be an object")
        val actual = node.properties().asSequence().map { it.key }.toSet()
        if (actual != allowed) throw InvalidShiftCoverageInput("callback envelope fields are not closed")
    }

    private data class Canonicalized(val node: JsonNode, val bytes: ByteArray)
}
