package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.codec.Base58
import java.nio.charset.StandardCharsets.UTF_8

/** bounded demo HTTP surface입니다. 이 limit은 application service 실행 전에 강제됩니다. */
internal data class VoucherHttpProperties(
    val maxHeaderLength: Int = 64,
    val maxScalarBytes: Int = 256,
    val maxPageSize: Int = 100,
    val maxCursorBytes: Int = 512,
    val operatorSecret: String = "local-operator-secret-0000000000000001",
    val operatorGuard: String = "voucher-workshop-operator",
    val allowedHosts: Set<String> = setOf("127.0.0.1", "localhost", "::1"),
)

internal data class ApiError(
    val code: String,
    val reason: String,
    val requestId: String,
    val retryAfterSeconds: Long? = null,
)

internal class VoucherApiException(
    val stableCode: String,
    val status: Int,
    val safeReason: String,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(stableCode)

internal fun requireAsciiIdentifier(
    value: String?,
    name: String,
    maxLength: Int = 64,
): String {
    val candidate = value ?: throw invalidRequest("$name is required")
    if (candidate.length !in 1..maxLength || candidate.toByteArray(UTF_8).size != candidate.length) {
        throw invalidRequest("$name must contain 1..$maxLength ASCII characters")
    }
    if (candidate.any { it.code !in 0x21..0x7e }) {
        throw invalidRequest("$name contains an invalid character")
    }
    return candidate
}

internal fun invalidRequest(reason: String = "request validation failed"): VoucherApiException =
    VoucherApiException("INVALID_REQUEST", 400, reason)

internal fun newRequestId(): String = Base58.randomString(8)

internal const val REQUEST_ID_ATTRIBUTE = "voucher.requestId"
internal const val REQUEST_ID_HEADER = "X-Request-Id"
internal const val TENANT_HEADER = "X-Workshop-Tenant"
internal const val PRINCIPAL_HEADER = "X-Workshop-Principal"
internal const val IDEMPOTENCY_HEADER = "Idempotency-Key"
internal const val OPERATOR_SECRET_HEADER = "X-Workshop-Operator-Secret"
internal const val OPERATOR_GUARD_HEADER = "X-Workshop-Guard"
