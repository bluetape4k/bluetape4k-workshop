package io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable

/**
 * Authors table backed by [AuditableLongIdTable].
 *
 * Inherits from bluetape4k-exposed-core [AuditableLongIdTable] which provides:
 * - `id` — auto-increment Long primary key
 * - `createdBy` — author of the insert (default: UserContext or "system")
 * - `createdAt` — DB CURRENT_TIMESTAMP on insert
 * - `updatedBy` — set on audited update via [AuditableJdbcRepository.auditedUpdateById]
 * - `updatedAt` — DB CURRENT_TIMESTAMP on audited update
 *
 * No manual `id` or `primaryKey` definition needed.
 */
object AuthorTable : AuditableLongIdTable("authors") {
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)
    val email = varchar("email", 255).uniqueIndex()
}
