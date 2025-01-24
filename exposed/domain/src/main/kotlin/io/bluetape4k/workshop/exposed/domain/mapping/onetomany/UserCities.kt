package io.bluetape4k.workshop.exposed.domain.mapping.onetomany

import io.bluetape4k.ToStringBuilder
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.Table

object UserTable: IntIdTable() {
    val name = varchar("name", 50).index()
    val age = integer("age")
}

object CityTable: IntIdTable() {
    val name = varchar("name", 50).index()
    val countryId = reference("country", CountryTable)  // many-to-one

    init {
        uniqueIndex(countryId, name)
    }
}

object CountryTable: IntIdTable() {
    val name = varchar("name", 50).uniqueIndex()
}

/**
 * City - User  Many-to-many relationship table
 */
object UserToCityTable: Table() {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val cityId = reference("city_id", CityTable, onDelete = ReferenceOption.CASCADE)
}

class User(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<User>(UserTable)

    var name by UserTable.name
    var age by UserTable.age
    var cities: SizedIterable<City> by City via UserToCityTable      // many-to-many

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("id", id)
            .add("name", name)
            .add("age", age)
            .toString()
    }
}

class City(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<City>(CityTable)

    var name by CityTable.name
    var country: Country by Country referencedOn CityTable.countryId   // many-to-one
    var users: SizedIterable<User> by User via UserToCityTable       // many-to-many

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("id", id)
            .add("name", name)
            .add("country", country)
            .toString()
    }
}

class Country(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<Country>(CountryTable)

    var name by CountryTable.name
    val cities: SizedIterable<City> by City referrersOn CityTable.countryId   // one-to-many

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("id", id)
            .add("name", name)
            .toString()
    }
}
