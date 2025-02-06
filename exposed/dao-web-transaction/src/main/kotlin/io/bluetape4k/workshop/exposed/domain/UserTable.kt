package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnTransformer
import org.jetbrains.exposed.sql.ColumnWithTransform
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.Table


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

    fun toUser(): User = User(
        id = id.value,
        name = name,
        age = age,
    )

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String = toStringBuilder()
        .add("name", name)
        .add("age", age)
        .toString()
}
