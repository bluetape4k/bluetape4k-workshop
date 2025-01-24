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

    override fun equals(other: Any?): Boolean = other is AccountEntity && this.id._value == other.id._value
    override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
    override fun toString(): String {
        return "AccountEntity(id=${id._value}, name=$name, balance=$balance)"
    }
}
