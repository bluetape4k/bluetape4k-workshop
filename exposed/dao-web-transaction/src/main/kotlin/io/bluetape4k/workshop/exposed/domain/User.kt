package io.bluetape4k.workshop.exposed.domain

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.io.Serializable

@JvmInline
value class UserId(val value: Long): Serializable

data class User(
    val id: UserId,
    val name: String,
    val age: Int,
): Serializable


class UserEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<UserEntity>(UserTable)

    var name by UserTable.name
    var age by UserTable.age

    fun toUser(): User = User(
        id = UserId(id.value),
        name = name,
        age = age,
    )

    override fun equals(other: Any?): Boolean = other is UserEntity && id._value == other.id._value
    override fun hashCode(): Int = id._value.hashCode()
    override fun toString(): String = "UserEntity(id=$id, name=$name, age=$age)"
}
