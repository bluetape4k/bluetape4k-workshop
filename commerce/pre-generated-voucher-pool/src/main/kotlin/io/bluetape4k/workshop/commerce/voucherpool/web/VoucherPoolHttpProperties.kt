@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.codec.Base58
import java.io.Serializable
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

/** voucher-pool workshop을 위한 bounded HTTP 및 local-demo authentication 설정입니다. */
internal data class VoucherPoolHttpProperties(
    val maxTenantLength: Int = 64,
    val maxPrincipalLength: Int = 64,
    val maxScalarBytes: Int = 256,
    val maxCursorBytes: Int = 512,
    val maxPayloadBytes: Int = 4 * 1024 * 1024,
    val minIdempotencyKeyLength: Int = 8,
    val maxIdempotencyKeyLength: Int = 200,
    val operatorSecret: String,
    val operatorGuard: String,
    val allowedHosts: Set<String> = setOf("127.0.0.1", "localhost", "::1"),
    val demoAuthEnabled: Boolean = true,
) : Serializable {
    override fun toString(): String =
        "VoucherPoolHttpProperties(maxTenantLength=$maxTenantLength,maxPrincipalLength=$maxPrincipalLength," +
            "maxScalarBytes=$maxScalarBytes,maxCursorBytes=$maxCursorBytes,maxPayloadBytes=$maxPayloadBytes," +
            "minIdempotencyKeyLength=$minIdempotencyKeyLength,maxIdempotencyKeyLength=$maxIdempotencyKeyLength," +
            "operatorSecret=[REDACTED],operatorGuard=[REDACTED],allowedHosts=$allowedHosts," +
            "demoAuthEnabled=$demoAuthEnabled)"

    companion object { private const val serialVersionUID: Long = 1L }
}

internal data class ApiError(
    val code: String,
    val reason: String,
    val requestId: String,
    val retryAfterSeconds: Long? = null,
    val effectId: UUID? = null,
) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

internal class VoucherPoolApiException(
    val stableCode: String,
    val status: Int,
    val safeReason: String,
    val retryAfterSeconds: Long? = null,
    val effectId: UUID? = null,
) : RuntimeException(stableCode)

internal fun invalidRequest(): VoucherPoolApiException =
    VoucherPoolApiException("INVALID_REQUEST", 400, "request validation failed")

internal fun resourceNotFound(): VoucherPoolApiException =
    VoucherPoolApiException("RESOURCE_NOT_FOUND", 404, "resource was not found")

internal fun requireBoundedAscii(
    value: String?,
    minLength: Int,
    maxLength: Int,
): String {
    val candidate = value ?: throw invalidRequest()
    if (candidate.length !in minLength..maxLength || candidate.any { it.code !in PRINTABLE_ASCII }) {
        throw invalidRequest()
    }
    return candidate
}

internal fun requireBoundedUtf8(value: String, maxBytes: Int): String {
    if (value.toByteArray(UTF_8).size > maxBytes || value.any(Character::isISOControl)) {
        throw invalidRequest()
    }
    return value
}

internal fun newRequestId(): String = Base58.randomString(12)

internal const val REQUEST_ID_ATTRIBUTE = "voucher-pool.requestId"
internal const val REQUEST_ID_HEADER = "X-Request-Id"
internal const val TENANT_HEADER = "X-Workshop-Tenant"
internal const val PRINCIPAL_HEADER = "X-Workshop-Principal"
internal const val IDEMPOTENCY_HEADER = "Idempotency-Key"
internal const val OPERATOR_SECRET_HEADER = "X-Workshop-Operator-Secret"
internal const val OPERATOR_GUARD_HEADER = "X-Workshop-Guard"

private val PRINTABLE_ASCII = 0x21..0x7e
