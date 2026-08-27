package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventHashMaterial
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Locale

object CanonicalEventHash {
    private val mapper: ObjectMapper = Jackson.defaultJsonMapper

    fun sha256(material: EventHashMaterial): String {
        require(material.streamVersion > 0) { "stream_version_invalid" }
        require(material.schemaVersion > 0) { "schema_version_invalid" }
        val canonical = buildString {
            append("billing-event-v1\n")
            append(material.stream.canonical()).append('\n')
            append(material.streamVersion).append('\n')
            append(material.eventType).append('\n')
            append(material.schemaVersion).append('\n')
            append(canonicalJson(material.payload)).append('\n')
            append(canonicalJson(material.metadata)).append('\n')
            append(material.previousHash.orEmpty())
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private fun canonicalJson(json: String): String = canonical(mapper.readTree(json))

    private fun canonical(node: JsonNode): String =
        when {
            node.isObject -> node.propertyNames().asSequence().sorted().joinToString(",", "{", "}") { name ->
                "${mapper.writeValueAsString(name)}:${canonical(node.get(name))}"
            }
            node.isArray -> (0 until node.size()).joinToString(",", "[", "]") { index -> canonical(node.get(index)) }
            node.isString -> mapper.writeValueAsString(node.stringValue())
            node.isNumber -> node.decimalValue().stripTrailingZeros().toPlainString()
            node.isBoolean -> node.booleanValue().toString()
            node.isNull -> "null"
            else -> error("unsupported_json_node")
        }
}
