package io.bluetape4k.workshop.commerce.voucher.idempotency

import io.bluetape4k.jackson3.jsonMapper
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/** Immutable SHA-256 or HMAC-sized value encoded as canonical Base64URL without padding. */
@JvmInline
internal value class Digest private constructor(
    val base64Url: String,
) {
    init {
        require(base64Url.length == SHA256_BASE64URL_LENGTH && BASE64URL.matches(base64Url)) {
            "digest must be a canonical 256-bit Base64URL value"
        }
    }

    companion object {
        fun of(bytes: ByteArray): Digest = Digest(encodeBase64Url(bytes.copyOf()))

        fun sha256(value: String): Digest = of(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))
    }
}

/** Secret acquisition capability. Persistence receives only [digest], never this raw token. */
@JvmInline
internal value class OwnerToken private constructor(
    val base64Url: String,
) {
    init {
        require(base64Url.length == SHA256_BASE64URL_LENGTH && BASE64URL.matches(base64Url)) {
            "owner token must be a canonical 256-bit Base64URL value"
        }
    }

    fun digest(): Digest = Digest.sha256("$OWNER_TOKEN_DOMAIN\u0000$base64Url")

    companion object {
        fun of(bytes: ByteArray): OwnerToken = OwnerToken(encodeBase64Url(bytes.copyOf()))

        fun random(random: SecureRandom = SECURE_RANDOM): OwnerToken =
            of(ByteArray(SHA256_BYTES).also(random::nextBytes))

        private val SECURE_RANDOM = SecureRandom()
    }
}

/** Closed scalar schema used to canonicalize the bounded voucher command DTO. */
internal sealed interface CanonicalField {
    val nullable: kotlin.Boolean
    val nullEquivalentToOmitted: kotlin.Boolean

    fun canonical(node: JsonNode): String

    data class Text(
        override val nullable: kotlin.Boolean = false,
        override val nullEquivalentToOmitted: kotlin.Boolean = false,
        val default: String? = null,
    ) : CanonicalField {
        override fun canonical(node: JsonNode): String {
            require(node.isString) { "expected a JSON string" }
            return jsonString(node.stringValue())
        }
    }

    data class Boolean(
        override val nullable: kotlin.Boolean = false,
        override val nullEquivalentToOmitted: kotlin.Boolean = false,
        val default: kotlin.Boolean? = null,
    ) : CanonicalField {
        override fun canonical(node: JsonNode): String {
            require(node.isBoolean) { "expected a JSON boolean" }
            return node.booleanValue().toString()
        }
    }

    data class Decimal(
        override val nullable: kotlin.Boolean = false,
        override val nullEquivalentToOmitted: kotlin.Boolean = false,
        val default: BigDecimal? = null,
    ) : CanonicalField {
        override fun canonical(node: JsonNode): String {
            require(node.isNumber) { "expected a JSON number" }
            return canonicalDecimal(node.decimalValue())
        }
    }
}

internal data class ClosedRequestSchema(
    val fields: Map<String, CanonicalField>,
) {
    init {
        require(fields.isNotEmpty()) { "closed request schema must declare fields" }
        require(fields.keys.all { it.isNotBlank() }) { "schema field names must not be blank" }
    }
}

/** Builds a domain-separated request fingerprint without retaining request body or raw keys. */
internal object IdempotencyFingerprint {
    private val mapper = jsonMapper { }

    fun key(
        tenantId: String,
        principalDigest: Digest,
        operation: String,
        resourceId: String,
        rawKey: String,
    ): Digest {
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(operation.isNotBlank()) { "operation must not be blank" }
        require(resourceId.isNotBlank()) { "resourceId must not be blank" }
        require(rawKey.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH) {
            "Idempotency-Key must contain $MIN_KEY_LENGTH..$MAX_KEY_LENGTH characters"
        }
        return Digest.sha256(
            listOf(KEY_DOMAIN, tenantId, principalDigest.base64Url, operation, resourceId, rawKey).joinToString("\u0000"),
        )
    }

    fun request(
        method: String,
        path: String,
        resourceId: String,
        headers: Map<String, String>,
        body: String,
        schema: ClosedRequestSchema,
    ): Digest {
        require(method.isNotBlank()) { "HTTP method must not be blank" }
        require(resourceId.isNotBlank()) { "resourceId must not be blank" }
        require(body.toByteArray(UTF_8).size <= MAX_BODY_BYTES) { "request body is too large" }

        val canonical =
            buildString {
                append(REQUEST_DOMAIN).append('\n')
                append(method.trim().uppercase(Locale.ROOT)).append('\n')
                append(normalizePath(path)).append('\n')
                append(resourceId.trim()).append('\n')
                append(canonicalHeaders(headers)).append('\n')
                append(canonicalBody(body, schema))
            }
        return Digest.sha256(canonical)
    }

    private fun canonicalHeaders(headers: Map<String, String>): String {
        val normalized =
            headers.entries
                .map { it.key.trim().lowercase(Locale.ROOT) to it.value.trim() }
                .filter { it.first in SEMANTIC_HEADERS }
                .groupBy({ it.first }, { it.second })
                .mapValues { (name, values) ->
                    require(values.distinct().size == 1) { "conflicting semantic header: $name" }
                    require(values.single().none { it == '\r' || it == '\n' }) {
                        "semantic header must not contain a line break: $name"
                    }
                    values.single()
                }
        return SEMANTIC_HEADERS.sorted().joinToString("\n") { name -> "$name=${normalized[name].orEmpty()}" }
    }

    private fun canonicalBody(
        body: String,
        schema: ClosedRequestSchema,
    ): String {
        val root =
            try {
                mapper.readTree(body)
            } catch (e: Exception) {
                throw IllegalArgumentException("request body must be valid JSON", e)
            }
        require(root.isObject) { "request body must be a JSON object" }

        val providedNames = root.propertyNames().asSequence().toSet()
        val unknown = providedNames - schema.fields.keys
        require(unknown.isEmpty()) { "unknown request properties: ${unknown.sorted().joinToString()}" }

        return schema.fields.keys.sortedWith(UTF8_COMPARATOR).mapNotNull { name ->
            val field = schema.fields.getValue(name)
            val node = root.get(name)
            when {
                node == null -> omittedEntry(name, field)
                node.isNull && field.nullEquivalentToOmitted -> omittedEntry(name, field)
                node.isNull && field.nullable -> "$name=null"
                node.isNull -> throw IllegalArgumentException("$name must not be null")
                else -> "$name=${field.canonical(node)}"
            }
        }.joinToString("\n")
    }

    private fun omittedEntry(
        name: String,
        field: CanonicalField,
    ): String? {
        val default =
            when (field) {
                is CanonicalField.Text -> field.default?.let { "$name=${jsonString(it)}" }
                is CanonicalField.Boolean -> field.default?.let { "$name=$it" }
                is CanonicalField.Decimal -> field.default?.let { "$name=${canonicalDecimal(it)}" }
            }
        if (default != null || field.nullEquivalentToOmitted) return default
        throw IllegalArgumentException("required request property is missing: $name")
    }

    private fun normalizePath(path: String): String {
        require(path.startsWith('/')) { "path must be absolute" }
        val normalized = path.trim().replace(Regex("/{2,}"), "/").removeSuffix("/")
        return normalized.ifEmpty { "/" }
    }

    private val UTF8_COMPARATOR = Comparator<String> { left, right ->
        compareUnsigned(left.toByteArray(UTF_8), right.toByteArray(UTF_8))
    }

    private fun compareUnsigned(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        val common = minOf(left.size, right.size)
        for (index in 0 until common) {
            val compared = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    private const val MAX_BODY_BYTES = 16 * 1024
    private const val MIN_KEY_LENGTH = 8
    private const val MAX_KEY_LENGTH = 200
    private const val KEY_DOMAIN = "voucher-http-idempotency-key-v1"
    private const val REQUEST_DOMAIN = "voucher-http-idempotency-request-v1"
    private val SEMANTIC_HEADERS =
        setOf("content-type", "x-workshop-principal", "x-workshop-tenant")
}

private fun canonicalDecimal(value: BigDecimal): String =
    value.stripTrailingZeros().let { if (it.compareTo(BigDecimal.ZERO) == 0) "0" else it.toPlainString() }

private fun jsonString(value: String): String =
    buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

private fun encodeBase64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private const val SHA256_BYTES = 32
private const val SHA256_BASE64URL_LENGTH = 43
private const val OWNER_TOKEN_DOMAIN = "voucher-http-idempotency-owner-v1"
private val BASE64URL = Regex("[A-Za-z0-9_-]+")
