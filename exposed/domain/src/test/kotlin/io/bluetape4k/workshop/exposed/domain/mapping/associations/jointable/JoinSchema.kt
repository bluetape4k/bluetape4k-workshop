package io.bluetape4k.workshop.exposed.domain.mapping.associations.jointable

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.selectAll

object JoinSchema {

    val faker = Fakers.faker

    val allTables = arrayOf(AddressTable, UserAddressTable, UserTable)

    fun withJoinSchema(testDB: TestDB, statement: Transaction.() -> Unit) {
        withTables(testDB, *allTables) {
            statement()
        }
    }

    object AddressTable: IntIdTable("join_address") {
        val street = varchar("street", 255).nullable()
        val city = varchar("city", 255).nullable()
        val zipcode = varchar("zipcode", 255).nullable()
    }

    object UserAddressTable: IntIdTable("user_address") {
        val userId = reference("user_id", UserTable, onDelete = CASCADE, onUpdate = CASCADE).index()
        val addressId = reference("address_id", AddressTable, onDelete = CASCADE, onUpdate = CASCADE)

        val type = varchar("addr_type", 255)

        init {
            uniqueIndex(type, userId)
        }
    }

    object UserTable: IntIdTable("join_user") {
        val name = varchar("name", 255)
    }

    class Address(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Address>(AddressTable)

        var street by AddressTable.street
        var city by AddressTable.city
        var zipcode by AddressTable.zipcode

        override fun equals(other: Any?): Boolean = other is Address && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String {
            return "Address(street=$street, city=$city, zipcode=$zipcode)"
        }
    }

    class UserAddress(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserAddress>(UserAddressTable)

        var type by UserAddressTable.type
        var user by User referencedOn UserAddressTable.userId              // one-to-many
        var address by Address referencedOn UserAddressTable.addressId   // one-to-many

        override fun equals(other: Any?): Boolean = other is UserAddress && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String {
            return "UserAddress(type=$type, user=${user.id._value}, address=${address.id._value})"
        }
    }

    class User(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<User>(UserTable)

        var name by UserTable.name
        val addresses: SizedIterable<Address> by Address.via(UserAddressTable.userId, UserAddressTable.addressId)

        // FIXME: many-to-many 형식에서 mapping table에 대한 one-to-many 관계를 표현하는 방법을 찾아야 한다.
        // val userAddresses: SizedIterable<UserAddress> by UserAddress referrersOn UserAddressTable.user // one-to-many
//        val addressMap
//            get() = userAddresses.associateBy { it.type }

        val userAddresses: SizedIterable<UserAddress>
            get() {
                val query = UserAddressTable.selectAll()
                    .where { UserAddressTable.userId eq id }
                    .orderBy(UserAddressTable.type)

                return UserAddress.wrapRows(query)
            }

        override fun equals(other: Any?): Boolean = other is User && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String {
            return "User(id=$id, name=$name)"
        }
    }

    fun newUser(): User {
        val user = User.new {
            name = faker.name().name()
        }
        val addr1 = Address.new {
            street = faker.address().streetAddress()
            city = faker.address().city()
            zipcode = faker.address().zipCode()
        }
        val addr2 = Address.new {
            street = faker.address().streetAddress()
            city = faker.address().city()
            zipcode = faker.address().zipCode()
        }

        UserAddress.new {
            this.type = "Home"
            this.user = user
            this.address = addr1
        }
        UserAddress.new {
            this.type = "Office"
            this.user = user
            this.address = addr2
        }
        return user
    }
}
