package io.bluetape4k.workshop.exposed.domain.mapping.entities

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable

object AccountTable: LongIdTable("accounts") {

    val name = varchar("name", 255)
    val balance = decimal("balance", 10, 2)
}

class AccountEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<AccountEntity>(AccountTable)

    var name by AccountTable.name
    var balance by AccountTable.balance

    override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
    override fun equals(other: Any?): Boolean = other is AccountEntity && other.id == this.id
    override fun toString(): String {
        return "AccountEntity(id=$id, name=$name, balance=$balance)"
    }
}
