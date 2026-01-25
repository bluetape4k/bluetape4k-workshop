package io.bluetape4k.workshop.exposed.domain.mapping.simple

import io.bluetape4k.exposed.dao.entityToStringBuilder
import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.jdbc.SizedIterable
import java.io.Serializable

/**
 * ```sql
 * -- Postgres
 * CREATE TABLE IF NOT EXISTS simple_entity (
 *      id BIGSERIAL PRIMARY KEY,
 *      "name" VARCHAR(255) NOT NULL,
 *      description TEXT NULL
 * );
 *
 * ALTER TABLE simple_entity
 *      ADD CONSTRAINT simple_entity_name_unique UNIQUE ("name");
 * ```
 *
 */
object SimpleTable: LongIdTable("simple_entity") {
    val name = varchar("name", 255).uniqueIndex()
    val description = text("description").nullable()
}

class SimpleEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<SimpleEntity>(SimpleTable)

    var name by SimpleTable.name
    var description by SimpleTable.description

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String = entityToStringBuilder()
        .add("name", name)
        .add("description", description)
        .toString()
}

data class SimpleEntityDto(
    val id: Long,
    val name: String,
    val description: String?,
): Serializable {
    companion object {
        fun from(entity: SimpleEntity): SimpleEntityDto {
            return SimpleEntityDto(
                id = entity.id.value,
                name = entity.name,
                description = entity.description
            )
        }

        fun wrapRow(resultRow: ResultRow): SimpleEntityDto {
            return SimpleEntityDto(
                id = resultRow[SimpleTable.id].value,
                name = resultRow[SimpleTable.name],
                description = resultRow[SimpleTable.description]
            )
        }

        fun wrapRows(rows: SizedIterable<ResultRow>): List<SimpleEntityDto> {
            return rows.map { wrapRow(it) }
        }
    }
}
