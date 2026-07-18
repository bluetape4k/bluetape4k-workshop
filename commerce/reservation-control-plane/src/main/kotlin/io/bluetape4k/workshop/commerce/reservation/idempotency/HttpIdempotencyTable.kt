package io.bluetape4k.workshop.commerce.reservation.idempotency

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

internal object HttpIdempotencyTable : AuditableLongIdTable("reservation_http_idempotency") {
    val tenantId = varchar("tenant_id", 80)
    val operation = varchar("operation", 80)
    val keyDigest = char("key_digest", 64)
    val requestFingerprint = char("request_fingerprint", 64)
    val status = enumerationByName<IdempotencyStatus>("status", 24)
    val ownerToken = javaUUID("owner_token")
    val leaseUntil = timestamp("lease_until")
    val responseStatus = integer("response_status").nullable()
    val responseBody = text("response_body").nullable()
    val expiresAt = timestamp("expires_at")

    init {
        uniqueIndex(tenantId, operation, keyDigest)
        index(false, expiresAt)
    }
}

/** Application-owned schema reference used by bootstrap and focused PostgreSQL tests. */
internal val reservationHttpIdempotencyTable = HttpIdempotencyTable
