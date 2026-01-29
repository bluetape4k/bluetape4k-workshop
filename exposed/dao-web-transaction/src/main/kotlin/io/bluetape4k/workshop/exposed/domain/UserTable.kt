package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.exposed.dao.entityToStringBuilder
import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnTransformer
import org.jetbrains.exposed.v1.core.ColumnWithTransform
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass


fun Table.userId(name: String): Column<UserId> =
    registerColumn(name, UserIdColumnType())

open class UserIdColumnType: ColumnWithTransform<Long, UserId>(LongColumnType(), LongToUserIdTransformer())

class LongToUserIdTransformer: ColumnTransformer<Long, UserId> {
    override fun unwrap(value: UserId): Long = value.value
    override fun wrap(value: Long): UserId = UserId(value)
}

/**
 * User 정보를 나타내는 Table
 *
 * ```sql
 * CREATE TABLE IF NOT EXISTS "User" (
 *      ID BIGSERIAL PRIMARY KEY,
 *      "name" VARCHAR(50) NOT NULL,
 *      AGE INT NOT NULL
 * )
 * ```
 */
object UserTable: IdTable<UserId>() {
    override val id: Column<EntityID<UserId>> = userId("id").autoIncrement().entityId()
    val name = varchar("name", length = 50)
    val age = integer("age")
}

class UserEntity(id: EntityID<UserId>): Entity<UserId>(id) {
    companion object: EntityClass<UserId, UserEntity>(UserTable)

    var name by UserTable.name
    var age by UserTable.age

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String = entityToStringBuilder()
        .add("name", name)
        .add("age", age)
        .toString()
}
