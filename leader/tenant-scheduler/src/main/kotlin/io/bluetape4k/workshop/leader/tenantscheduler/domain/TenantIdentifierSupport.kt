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
    require(raw.none { it.isISOControl() }) {
        "$fieldName must not contain control characters"
    }
    require(!raw.any { it.isWhitespace() }) {
        "$fieldName must not contain whitespace"
    }
    require(!EMAIL_LIKE_PATTERN.matches(raw)) {
        "$fieldName must not be an email-like value"
    }
    require(!ACCOUNT_ID_LIKE_PATTERN.matches(normalized)) {
        "$fieldName must not be an account-id-shaped value"
    }
    require(SAFE_ALIAS_PATTERN.matches(normalized)) {
        "$fieldName must match a safe lowercase alias pattern"
    }

    return normalized
}
