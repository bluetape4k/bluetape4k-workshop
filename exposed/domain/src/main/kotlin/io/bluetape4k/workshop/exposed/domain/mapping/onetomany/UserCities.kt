package io.bluetape4k.workshop.exposed.domain.mapping.onetomany

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object UserTable: IntIdTable() {
    val name = varchar("name", 50).index()
    val age = integer("age")
}

object CityTable: IntIdTable() {
    val name = varchar("name", 50).index()
    val country = reference("country", CountryTable)  // one-to-many
}

object CountryTable: IntIdTable() {
    val name = varchar("name", 50)
}

/**
 * Many-to-many relationship table
 */
object UserToCityTable: Table() {
    val user = reference("user", UserTable, onDelete = ReferenceOption.CASCADE)
    val city = reference("city", CityTable, onDelete = ReferenceOption.CASCADE)
}

class User(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<User>(UserTable)

    var name by UserTable.name
    var age by UserTable.age
    val cities by City via UserToCityTable
}

class City(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<City>(CityTable)

    var name by CityTable.name
    var users by User via UserToCityTable
    var country by Country referencedOn CityTable.country
}

class Country(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<Country>(CountryTable)

    var name by CountryTable.name
    val cities by City referrersOn CityTable.country   // one-to-many
}
