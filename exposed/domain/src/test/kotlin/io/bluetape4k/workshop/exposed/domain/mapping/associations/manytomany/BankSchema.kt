package io.bluetape4k.workshop.exposed.domain.mapping.associations.manytomany

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption.RESTRICT
import org.jetbrains.exposed.sql.Table

/**
 * 은행 계좌 - 계좌 소유자에 대한 Many-to-Many 관계를 나타내는 스키마
 */
object BankSchema {

    val allTables = arrayOf(BankAccountTable, AccountOwnerTable, OwnerAccountMapTable)

    /**
     * 은행 계좌 테이블
     */
    object BankAccountTable: IntIdTable("bank_account") {
        val number = varchar("number", 255).uniqueIndex()
    }

    /**
     * 계좌 소유자 테이블
     */
    object AccountOwnerTable: IntIdTable("account_owner") {
        val ssn = varchar("ssn", 255).uniqueIndex()
    }

    /**
     * 은행 계좌 - 계좌 소유자에 대한 Many-to-Many Mapping Table
     */
    object OwnerAccountMapTable: Table("owner_account_map") {
        val ownerId = reference("owner_id", AccountOwnerTable, onDelete = RESTRICT)
        val accountId = reference("account_id", BankAccountTable, onDelete = RESTRICT)
    }

    /**
     * 은행 계좌
     */
    class BankAccount(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<BankAccount>(BankAccountTable)

        var number by BankAccountTable.number
        val owners by AccountOwner via OwnerAccountMapTable  // many-to-many

        override fun equals(other: Any?): Boolean = other is BankAccount && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "BankAccount(id=${id._value}, number=$number)"
    }

    /**
     * 계좌 소유자
     */
    class AccountOwner(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<AccountOwner>(AccountOwnerTable)

        var ssn by AccountOwnerTable.ssn
        val accounts by BankAccount via OwnerAccountMapTable // many-to-many

        override fun equals(other: Any?): Boolean = other is AccountOwner && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "AccountOwner(id=${id._value}, ssn=$ssn)"
    }
}
