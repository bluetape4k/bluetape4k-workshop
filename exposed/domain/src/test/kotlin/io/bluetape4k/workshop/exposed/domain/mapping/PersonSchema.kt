package io.bluetape4k.workshop.exposed.domain.mapping

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.javatime.date
import java.time.LocalDate

object PersonSchema {

    val allPersonTables = arrayOf(AddressTable, PersonTable)

    object AddressTable: LongIdTable("addresses") {
        val street = varchar("street", 255)
        val city = varchar("city", 255)
        val state = varchar("state", 2)
        val zip = varchar("zip", 10).nullable()
    }

    object PersonTable: LongIdTable("persons") {
        val firstName = varchar("first_name", 50)
        val lastName = varchar("last_name", 50)
        val birthDate = date("birth_date")
        val employeed = bool("employeed").default(true)
        val occupation = varchar("occupation", 255).nullable()
        val addressId = reference("address_id", AddressTable)  // many to one
    }

    class Address(id: EntityID<Long>): LongEntity(id), java.io.Serializable {
        companion object: LongEntityClass<Address>(AddressTable)

        var street by AddressTable.street
        var city by AddressTable.city
        var state by AddressTable.state
        var zip by AddressTable.zip
    }

    class Person(id: EntityID<Long>): LongEntity(id), java.io.Serializable {
        companion object: LongEntityClass<Person>(PersonTable)

        var firstName by PersonTable.firstName
        var lastName by PersonTable.lastName
        var birthDate by PersonTable.birthDate
        var employeed by PersonTable.employeed
        var occupation by PersonTable.occupation
        var address by Address referencedOn PersonTable.addressId
    }

    data class PersonWithAddress(
        var id: Long? = null,
        var firstName: String? = null,
        var lastName: String? = null,
        var birthDate: java.time.LocalDate? = null,
        var employeed: Boolean? = null,
        var occupation: String? = null,
        var address: Address? = null,
    ): java.io.Serializable

    fun withPersons(
        testDB: TestDB,
        block: Transaction.(PersonTable, AddressTable) -> Unit,
    ) {
        withTables(testDB, *allPersonTables) {


            block(PersonTable, AddressTable)
        }
    }
}

fun AbstractExposedTest.withPersonsAndAddress(
    testDB: TestDB,
    statement: Transaction.(
        persons: PersonSchema.PersonTable,
        addresses: PersonSchema.AddressTable,
    ) -> Unit,
) {
    val persons = PersonSchema.PersonTable
    val addresses = PersonSchema.AddressTable

    withTables(testDB, *PersonSchema.allPersonTables) {

        val addr1 = PersonSchema.Address.new {
            street = "123 Main St"
            city = "Bedrock"
            state = "IN"
            zip = "12345"
        }
        val addr2 = PersonSchema.Address.new {
            street = "456 Elm St"
            city = "Bedrock"
            state = "IN"
            zip = "12345"
        }

        PersonSchema.Person.new {
            firstName = "Fred"
            lastName = "Flintstone"
            birthDate = LocalDate.of(1935, 2, 1)
            employeed = true
            occupation = "Brontosaurus Operator"
            address = addr1
        }
        PersonSchema.Person.new {
            firstName = "Wilma"
            lastName = "Flintstone"
            birthDate = LocalDate.of(1940, 2, 1)
            employeed = false
            occupation = "Accountant"
            address = addr1
        }
        PersonSchema.Person.new {
            firstName = "Pebbles"
            lastName = "Flintstone"
            birthDate = LocalDate.of(1960, 5, 6)
            employeed = false
            address = addr1
        }
        PersonSchema.Person.new {
            firstName = "Barney"
            lastName = "Rubble"
            birthDate = LocalDate.of(1937, 2, 1)
            employeed = true
            occupation = "Brontosaurus Operator"
            address = addr2
        }
        PersonSchema.Person.new {
            firstName = "Betty"
            lastName = "Rubble"
            birthDate = LocalDate.of(1943, 2, 1)
            employeed = false
            occupation = "Engineer"
            address = addr2
        }
        PersonSchema.Person.new {
            firstName = "Bamm Bamm"
            lastName = "Rubble"
            birthDate = LocalDate.of(1963, 7, 8)
            employeed = false
            address = addr2
        }

        statement(persons, addresses)
    }
}
