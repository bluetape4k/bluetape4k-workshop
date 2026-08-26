package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Kafka value를 위한 제한된 JSON 문자열 codec입니다.
 *
 * schema를 명시적으로 작성하여 Spring Kafka type header나 polymorphic typing에
 * 의존하지 않습니다. decode는 허용된 네 필드와 정확한 scalar type만 받아들입니다.
 */
class KafkaFailoverCodec {

    /**
     * event를 canonical field order의 JSON 문자열로 인코딩합니다.
     */
    fun encode(event: KafkaFailoverEvent): String {
        val node = mapper.createObjectNode()
            .put(EVENT_ID_FIELD, event.eventId)
            .put(SEQUENCE_FIELD, event.sequence)
            .put(PAYLOAD_FIELD, event.payload)
            .put(PARTITION_KEY_FIELD, event.partitionKey)

        return mapper.writeValueAsString(node)
    }

    /**
     * canonical event schema를 검증하며 JSON 문자열을 decode합니다.
     *
     * @throws IllegalArgumentException JSON이 schema 또는 입력 제한을 위반할 때 발생합니다.
     */
    fun decode(json: String): KafkaFailoverEvent {
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_DOCUMENT_BYTES) {
            throw invalidJson()
        }

        val root = try {
            mapper.createParser(json).use { parser ->
                val value = mapper.readTree(parser)
                if (parser.nextToken() != null) {
                    throw invalidJson()
                }
                value
            }
        } catch (_: JacksonException) {
            throw invalidJson()
        }

        if (root == null || !root.isObject) {
            throw invalidJson("JSON root must be an object")
        }

        val fields = root.propertyNames().toSet()
        if (fields != EXPECTED_FIELDS) {
            throw invalidJson("JSON fields do not match the reference schema")
        }

        val eventId = root.requiredText(EVENT_ID_FIELD)
        val sequence = root.requiredSequence()
        val payload = root.requiredText(PAYLOAD_FIELD)
        val partitionKey = root.requiredText(PARTITION_KEY_FIELD)

        return try {
            KafkaFailoverEvent(
                eventId = eventId,
                sequence = sequence,
                payload = payload,
                partitionKey = partitionKey,
            )
        } catch (error: IllegalArgumentException) {
            throw invalidJson(error.message ?: "JSON event values are invalid")
        }
    }

    /**
     * canonical UTF-8 JSON의 SHA-256 fingerprint를 반환합니다.
     */
    fun fingerprint(event: KafkaFailoverEvent): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(encode(event).toByteArray(StandardCharsets.UTF_8))

        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private fun JsonNode.requiredText(fieldName: String): String {
        val value = get(fieldName)
        if (value == null || !value.isTextual) {
            throw invalidJson("JSON field $fieldName must be a string")
        }
        return value.stringValue()
    }

    private fun JsonNode.requiredSequence(): Long {
        val value = get(SEQUENCE_FIELD)
        if (value == null || !value.isIntegralNumber || !value.canConvertToLong()) {
            throw invalidJson("JSON field $SEQUENCE_FIELD must be an integer")
        }
        return value.longValue()
    }

    private fun invalidJson(message: String = "Invalid Kafka failover event JSON"): IllegalArgumentException =
        IllegalArgumentException(message)

    private companion object {
        const val EVENT_ID_FIELD = "eventId"
        const val SEQUENCE_FIELD = "sequence"
        const val PAYLOAD_FIELD = "payload"
        const val PARTITION_KEY_FIELD = "partitionKey"
        const val MAX_DOCUMENT_BYTES = 16 * 1024
        val EXPECTED_FIELDS = setOf(EVENT_ID_FIELD, SEQUENCE_FIELD, PAYLOAD_FIELD, PARTITION_KEY_FIELD)
        val HEX = "0123456789abcdef".toCharArray()

        val mapper: JsonMapper = JsonMapper.builder(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxNestingDepth(32)
                        .maxDocumentLength(MAX_DOCUMENT_BYTES.toLong())
                        .maxTokenCount(128)
                        .maxNumberLength(20)
                        .maxStringLength(8 * 1024)
                        .maxNameLength(64)
                        .build()
                )
                .build()
        )
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            )
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build()
    }
}
