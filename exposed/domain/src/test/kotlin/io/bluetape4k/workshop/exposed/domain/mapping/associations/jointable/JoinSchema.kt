package io.bluetape4k.workshop.exposed.domain.mapping.associations.jointable

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SizedIterable
import org.jetbrains.exposed.v1.jdbc.selectAll

object JoinSchema {

    val faker = Fakers.faker

    val allTables = arrayOf(AddressTable, UserAddressTable, UserTable)

    fun withJoinSchema(testDB: TestDB, statement: JdbcTransaction.() -> Unit) {
        withTables(testDB, *allTables) {
            statement()
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS join_address (
     *      id SERIAL PRIMARY KEY,
     *      street VARCHAR(255) NULL,
     *      city VARCHAR(255) NULL,
     *      zipcode VARCHAR(255) NULL
     * )
     * ```
     */
    object AddressTable: IntIdTable("join_address") {
        val street = varchar("street", 255).nullable()
        val city = varchar("city", 255).nullable()
        val zipcode = varchar("zipcode", 255).nullable()
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS user_address (
     *      id SERIAL PRIMARY KEY,
     *      user_id INT NOT NULL,
     *      address_id INT NOT NULL,
     *      addr_type VARCHAR(255) NOT NULL,
     *
     *      CONSTRAINT fk_user_address_user_id__id FOREIGN KEY (user_id)
     *      REFERENCES join_user(id) ON DELETE CASCADE ON UPDATE CASCADE,
     *
     *      CONSTRAINT fk_user_address_address_id__id FOREIGN KEY (address_id)
     *      REFERENCES join_address(id) ON DELETE CASCADE ON UPDATE CASCADE
     * );
     *
     * CREATE INDEX user_address_user_id ON user_address (user_id);
     *
     * ALTER TABLE user_address
     *      ADD CONSTRAINT user_address_addr_type_user_id_unique UNIQUE (addr_type, user_id);
     * ```
     */
    object UserAddressTable: IntIdTable("user_address") {
        val userId = reference(
            "user_id",
            UserTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
        val addressId = reference(
            "address_id",
            AddressTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )

        val type = varchar("addr_type", 255)

        init {
            uniqueIndex(type, userId)
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS join_user (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * )
     * ```
     */
    object UserTable: IntIdTable("join_user") {
        val name = varchar("name", 255)
    }

    class Address(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Address>(AddressTable)

        var street by AddressTable.street
        var city by AddressTable.city
        var zipcode by AddressTable.zipcode

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String {
            return toStringBuilder()
                .add("street", street)
                .add("city", city)
                .add("zipcode", zipcode)
                .toString()
        }
    }

    class UserAddress(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<UserAddress>(UserAddressTable)

        var type by UserAddressTable.type
        var user by User referencedOn UserAddressTable.userId              // one-to-many
        var address by Address referencedOn UserAddressTable.addressId   // one-to-many

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("type", type)
                .add("user id", user.id._value)
                .add("address id", address.id._value)
                .toString()
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

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = toStringBuilder()
            .add("name", name)
            .toString()
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
