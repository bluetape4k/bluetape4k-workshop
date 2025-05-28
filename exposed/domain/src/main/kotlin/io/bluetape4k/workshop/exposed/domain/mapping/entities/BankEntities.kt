package io.bluetape4k.workshop.exposed.domain.mapping.entities

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

object AccountTable: LongIdTable("accounts") {

    val name = varchar("name", 255)
    val balance = decimal("balance", 10, 2)
}

class AccountEntity(id: EntityID<Long>): LongEntity(id) {
    companion object: LongEntityClass<AccountEntity>(AccountTable)

    var name by AccountTable.name
    var balance by AccountTable.balance

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String = toStringBuilder()
        .add("name", name)
        .add("balance", balance)
        .toString()
}
