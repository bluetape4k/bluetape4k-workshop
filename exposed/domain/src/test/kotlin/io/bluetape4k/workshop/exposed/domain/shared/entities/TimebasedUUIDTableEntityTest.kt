package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Address
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Addresses
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Cities
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.City
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.People
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Person
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Town
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Towns
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insertAndGetId
import org.junit.jupiter.api.Test
import java.util.*

object TimebasedUUIDTables {
    object Cities: TimebasedUUIDTable() {
        val name = varchar("name", 50)
    }

    class City(id: EntityID<UUID>): UUIDEntity(id) {
        companion object: UUIDEntityClass<City>(Cities)

        var name by Cities.name
        val towns by Town referrersOn Towns.cityId
    }

    object People: TimebasedUUIDTable() {
        val name = varchar("name", 80)
        val cityId = reference("city_id", Cities)
    }

    class Person(id: EntityID<UUID>): UUIDEntity(id) {
        companion object: UUIDEntityClass<Person>(People)

        var name by People.name
        var city by City referencedOn People.cityId
    }

    object Addresses: TimebasedUUIDTable() {
        val person = reference("person_id", People)
        val city = reference("city_id", Cities)
        val address = varchar("address", 255)
    }

    class Address(id: EntityID<UUID>): UUIDEntity(id) {
        companion object: UUIDEntityClass<Address>(Addresses)

        var person by Person.referencedOn(Addresses.person)
        var city by City.referencedOn(Addresses.city)
        var address by Addresses.address
    }

    object Towns: TimebasedUUIDTable("towns") {
        val cityId = uuid("city_id").references(Cities.id)
    }

    class Town(id: EntityID<UUID>): UUIDEntity(id) {
        companion object: UUIDEntityClass<Town>(Towns)

        var city by City referencedOn Towns.cityId
    }
}

class TimebasedUUIDTableEntityTest: AbstractExposedTest() {

    @Test
    fun `create tables`() {
        withTables(Cities, People) {
            Cities.exists().shouldBeTrue()
            People.exists().shouldBeTrue()
        }
    }

    @Test
    fun `create records`() {
        withTables(Cities, People) {
            val seoul = City.new { name = "Seoul" }
            val busan = City.new { name = "Busan" }

            Person.new(TimebasedUuid.Epoch.nextId()) {
                name = "Debop"
                city = seoul
            }
            Person.new(TimebasedUuid.Reordered.nextId()) {
                name = "BTS"
                city = seoul
            }
            Person.new {
                name = "Sam"
                city = busan
            }

            entityCache.clear(flush = true)

            val allCities = City.all().map { it.name }
            allCities shouldBeEqualTo listOf("Seoul", "Busan")

            val allPeople = Person.all().map { it.name to it.city.name }
            allPeople shouldBeEqualTo listOf(
                "Debop" to "Seoul",
                "BTS" to "Seoul",
                "Sam" to "Busan"
            )
        }
    }

    @Test
    fun `update and delete records`() {
        withTables(Cities, People) {
            val seoul = City.new { name = "Seoul" }
            val busan = City.new { name = "Busan" }

            Person.new(TimebasedUuid.Epoch.nextId()) {
                name = "Debop"
                city = seoul
            }
            Person.new(TimebasedUuid.Reordered.nextId()) {
                name = "BTS"
                city = seoul
            }
            val sam = Person.new {
                name = "Sam"
                city = busan
            }

            sam.delete()
            busan.delete()

            val allCities = City.all().map { it.name }
            allCities shouldBeEqualTo listOf("Seoul")

            val allPeople = Person.all().map { it.name to it.city.name }
            allPeople shouldBeEqualTo listOf(
                "Debop" to "Seoul",
                "BTS" to "Seoul",
            )
        }
    }

    @Test
    fun `insert with inner table`() {
        withTables(Addresses, Cities, People) {
            val city1 = City.new { name = "City1" }
            val person1 = Person.new {
                name = "Person1"
                city = city1
            }
            val address1 = Address.new {
                person = person1
                city = city1
                address = "Address1"
            }
            val address2 = Address.new {
                person = person1
                city = city1
                address = "Address2"
            }

            address1.refresh(flush = true)
            address1.address shouldBeEqualTo "Address1"

            address2.refresh(flush = true)
            address2.address shouldBeEqualTo "Address2"
        }
    }

    @Test
    fun `foreign key between uuid and entity id column`() {
        withTables(Cities, Towns) {
            val cId = Cities.insertAndGetId {
                it[name] = "City A"
            }
            val tId = Towns.insertAndGetId {
                it[cityId] = cId.value
            }
            val tId2 = Towns.insertAndGetId {
                it[cityId] = cId.value
            }

            entityCache.clear(flush = true)

            // lazy loading referencedOn
            log.debug { "Lazy loading referencedOn" }
            val town1 = Town.all().first()
            town1.city.id shouldBeEqualTo cId

            // eager loading referencedOn
            log.debug { "Eager loading referencedOn" }
            val town1WithCity = Town.all().with(Town::city).first()
            town1WithCity.city.id shouldBeEqualTo cId

            // lazy loading referrersOn
            log.debug { "Lazy loading referrersOn" }
            val city1 = City.all().single()
            city1.towns.first().city.id shouldBeEqualTo cId

            // eager loading referrersOn
            log.debug { "Eager loading referrersOn" }
            val city1WithTowns = City.all().with(City::towns).single()
            city1WithTowns.towns.first().id shouldBeEqualTo tId
        }
    }
}
