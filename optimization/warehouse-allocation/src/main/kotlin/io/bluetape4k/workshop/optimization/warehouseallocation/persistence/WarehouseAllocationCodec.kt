package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import io.bluetape4k.jackson3.CanonicalJson
import io.bluetape4k.jackson3.CanonicalJsonLimits
import io.bluetape4k.jackson3.CanonicalJsonStringNormalization
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.WarehouseAllocationLimits
import io.bluetape4k.workshop.optimization.warehouseallocation.domain.PlanProposal
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

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

    private val canonicalJson = CanonicalJson(
        limits = CanonicalJsonLimits(
            maxBodyBytes = WarehouseAllocationLimits.MAX_BODY_BYTES,
            maxDepth = 12,
            maxStringLength = WarehouseAllocationLimits.MAX_BODY_BYTES,
            maxNameLength = WarehouseAllocationLimits.MAX_EVENT_KEY,
            maxObjectEntries = WarehouseAllocationLimits.MAX_BODY_BYTES,
            maxArrayElements = WarehouseAllocationLimits.MAX_BODY_BYTES,
            maxOutputBytes = WarehouseAllocationLimits.MAX_BODY_BYTES,
        ),
        stringNormalization = CanonicalJsonStringNormalization.NFC,
    )

    fun encode(value: Any): String = canonicalJson.canonicalBytes(mapper.valueToTree(value)).toString(UTF_8)

    fun <T> decode(value: String, type: Class<T>): T = mapper.readValue(value, type)

    fun decodePlan(value: String): PlanProposal = mapper.readValue(value, PlanProposal::class.java)

    fun canonicalBytes(body: ByteArray): ByteArray {
        require(body.isNotEmpty() && body.size <= WarehouseAllocationLimits.MAX_BODY_BYTES) { "body exceeds 256KiB" }
        return canonicalJson.canonicalBytes(body)
    }

    fun digest(value: Any): String = sha256(encode(value).toByteArray(UTF_8))

    fun digestBytes(body: ByteArray): String = sha256(canonicalBytes(body))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
