package io.bluetape4k.workshop.exposed.mvc.jdbc.author.schema

import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable

/**
 * [AuditableLongIdTable]을 기반으로 하는 authors table이다.
 *
 * bluetape4k-exposed-core의 [AuditableLongIdTable]을 상속하며 다음 값을 제공받는다.
 * - `id` - auto-increment Long primary key
 * - `createdBy` - insert 작성자이다. 기본값은 UserContext 또는 "system"이다.
 * - `createdAt` - insert 시 DB CURRENT_TIMESTAMP 값이다.
 * - `updatedBy` - [AuditableJdbcRepository.auditedUpdateById]를 통한 audited update 시 설정된다.
 * - `updatedAt` - audited update 시 DB CURRENT_TIMESTAMP 값이다.
 *
 * 별도의 `id`나 `primaryKey` 정의가 필요하지 않다.
 */
object AuthorTable : AuditableLongIdTable("authors") {
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)
    val email = varchar("email", 255).uniqueIndex()
}
