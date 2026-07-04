package io.bluetape4k.workshop.leader.tenantscheduler.domain

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import java.util.Locale

private val SAFE_ALIAS_PATTERN = Regex("[a-z][a-z0-9-]*[a-z0-9]")
private val EMAIL_LIKE_PATTERN = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
private val ACCOUNT_ID_LIKE_PATTERN = Regex("^(acct|aws|customer)-[0-9]{6,}$")

internal fun normalizeTenantAlias(raw: String, fieldName: String): String {
    raw.requireNotBlank(fieldName)

    val normalized = raw.lowercase(Locale.ROOT)

    normalized.length.requireInRange(3, 64, "$fieldName.length")
    raw.count { it.isISOControl() }.requireInRange(0, 0, "$fieldName.controlCharacters")
    raw.count { it.isWhitespace() }.requireInRange(0, 0, "$fieldName.whitespace")
    EMAIL_LIKE_PATTERN.matches(raw).toViolationCount().requireInRange(0, 0, "$fieldName.emailLike")
    ACCOUNT_ID_LIKE_PATTERN.matches(normalized).toViolationCount().requireInRange(0, 0, "$fieldName.accountIdLike")
    SAFE_ALIAS_PATTERN.matches(normalized).toMissingCount().requireInRange(0, 0, "$fieldName.safeAlias")

    return normalized
}

private fun Boolean.toViolationCount(): Int = if (this) 1 else 0

private fun Boolean.toMissingCount(): Int = if (this) 0 else 1
