package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable
import io.bluetape4k.idgenerators.uuid.TimebasedUuid.Epoch
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Address
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Addresses
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Cities
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.City
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.People
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Person
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Town
import io.bluetape4k.workshop.exposed.domain.shared.entities.TimebasedUUIDTables.Towns
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*

object TimebasedUUIDTables {
    object Cities: TimebasedUUIDTable() {
        val name = varchar("name", 50)
    }

    class City(id: EntityID<UUID>): TimebasedUUIDEntity(id) {
        companion object: TimebasedUUIDEntityClass<City>(Cities)

        var name by Cities.name
        val towns by Town referrersOn Towns.cityId
    }

    object People: TimebasedUUIDTable() {
        val name = varchar("name", 80)
        val cityId = reference("city_id", Cities)
    }

    class Person(id: EntityID<UUID>): TimebasedUUIDEntity(id) {
        companion object: TimebasedUUIDEntityClass<Person>(People)

        var name by People.name
        var city by City referencedOn People.cityId
    }

    object Addresses: TimebasedUUIDTable() {
        val person = reference("person_id", People)
        val city = reference("city_id", Cities)
        val address = varchar("address", 255)
    }

    class Address(id: EntityID<UUID>): TimebasedUUIDEntity(id) {
        companion object: TimebasedUUIDEntityClass<Address>(Addresses)

        var person by Person.referencedOn(Addresses.person)
        var city by City.referencedOn(Addresses.city)
        var address by Addresses.address
    }

    object Towns: TimebasedUUIDTable("towns") {
        val cityId = uuid("city_id").references(Cities.id)
    }

    class Town(id: EntityID<UUID>): TimebasedUUIDEntity(id) {
        companion object: TimebasedUUIDEntityClass<Town>(Towns)

        var city by City referencedOn Towns.cityId
    }
}

class TimebasedUUIDTableEntityTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create tables`(testDB: TestDB) {
        withTables(testDB, Cities, People) {
            Cities.exists().shouldBeTrue()
            People.exists().shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create records`(testDB: TestDB) {
        withTables(testDB, Cities, People) {
            val seoul = City.new { name = "Seoul" }
            val busan = City.new { name = "Busan" }

            Person.new(Epoch.nextId()) {
                name = "Debop"
                city = seoul
            }
            Person.new(Epoch.nextId()) {
                name = "BTS"
                city = seoul
            }
            Person.new {
                name = "Sam"
                city = busan
            }

            flushCache()

            /**
             * ```sql
             * SELECT CITIES.ID, CITIES."name" FROM CITIES
             * ```
             */
            /**
             * ```sql
             * SELECT CITIES.ID, CITIES."name" FROM CITIES
             * ```
             */
            val allCities = City.all().map { it.name }
            allCities shouldBeEqualTo listOf("Seoul", "Busan")

            /**
             * ```sql
             * SELECT PEOPLE.ID, PEOPLE."name", PEOPLE.CITY_ID FROM PEOPLE
             *
             * SELECT CITIES.ID, CITIES."name"
             *   FROM CITIES
             *  WHERE CITIES.ID IN ('1efcff30-9a92-6fd6-b98d-178b68d550e5', '1efcff30-9a92-6fd8-b98d-178b68d550e5')
             * ```
             */
            /**
             * ```sql
             * SELECT PEOPLE.ID, PEOPLE."name", PEOPLE.CITY_ID FROM PEOPLE
             *
             * SELECT CITIES.ID, CITIES."name"
             *   FROM CITIES
             *  WHERE CITIES.ID IN ('1efcff30-9a92-6fd6-b98d-178b68d550e5', '1efcff30-9a92-6fd8-b98d-178b68d550e5')
             * ```
             */
            val allPeople = Person.all().with(Person::city).map { it.name to it.city.name }
            allPeople shouldBeEqualTo listOf(
                "Debop" to "Seoul",
                "BTS" to "Seoul",
                "Sam" to "Busan"
            )
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `update and delete records`(testDb: TestDB) {
        withTables(testDb, Cities, People) {
            val seoul = City.new { name = "Seoul" }
            val busan = City.new { name = "Busan" }

            Person.new(Epoch.nextId()) {
                name = "Debop"
                city = seoul
            }
            Person.new(Epoch.nextId()) {
                name = "BTS"
                city = seoul
            }
            val sam = Person.new {
                name = "Sam"
                city = busan
            }

            // DELETE FROM PEOPLE WHERE PEOPLE.ID = '1efcff30-9a33-6c52-b98d-178b68d550e5'
            sam.delete()
            // DELETE FROM CITIES WHERE CITIES.ID = '1efcff30-9a2e-6e2d-b98d-178b68d550e5'
            busan.delete()

            flushCache()

            val allCities = City.all().map { it.name }
            allCities shouldBeEqualTo listOf("Seoul")

            /**
             * ```sql
             * SELECT PEOPLE.ID, PEOPLE."name", PEOPLE.CITY_ID FROM PEOPLE
             *
             * SELECT CITIES.ID, CITIES."name"
             *   FROM CITIES
             *  WHERE CITIES.ID = '1efcff30-9a2e-6e2b-b98d-178b68d550e5'
             * ```
             */
            /**
             * ```sql
             * SELECT PEOPLE.ID, PEOPLE."name", PEOPLE.CITY_ID FROM PEOPLE
             *
             * SELECT CITIES.ID, CITIES."name"
             *   FROM CITIES
             *  WHERE CITIES.ID = '1efcff30-9a2e-6e2b-b98d-178b68d550e5'
             * ```
             */
            val allPeople = Person.all().with(Person::city).map { it.name to it.city.name }
            allPeople shouldBeEqualTo listOf(
                "Debop" to "Seoul",
                "BTS" to "Seoul",
            )
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert with inner table`(testDb: TestDB) {
        withTables(testDb, Addresses, Cities, People) {
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

    /**
     * Lazy loading referencedOn
     * ```sql
     * SELECT towns.id, towns.city_id FROM towns
     * SELECT Cities.id, Cities.`name` FROM Cities WHERE Cities.id = '1efcc4a5-73e6-6e8a-830f-399275c6fb04'
     * ```
     *
     * Eager loading referencedOn
     * ```sql
     * SELECT towns.id, towns.city_id FROM towns
     * SELECT Cities.id, Cities.`name` FROM Cities WHERE Cities.id = '1efcc4a5-73e6-6e8a-830f-399275c6fb04'
     * SELECT Cities.id, Cities.`name` FROM Cities WHERE Cities.id = '1efcc4a5-73e6-6e8a-830f-399275c6fb04'
     * ```
     *
     * Lazy loading referrersOn
     * ```sql
     * SELECT Cities.id, Cities.`name` FROM Cities
     * SELECT towns.id, towns.city_id FROM towns WHERE towns.city_id = '1efcc4a5-73e6-6e8a-830f-399275c6fb04'
     * SELECT Cities.id, Cities.`name` FROM Cities WHERE Cities.id = '1efcc4a5-73e6-6e8a-830f-399275c6fb04'
     * ```
     *
     * Eager loading referrersOn
     * ```sql
     * SELECT Cities.id, Cities.`name` FROM Cities
     * SELECT towns.id, towns.city_id, Cities.id FROM towns INNER JOIN Cities ON towns.city_id = Cities.id WHERE towns.city_id = '1efcc4a5-73e6-6e8a-830f-399275c6fb04'
     * SELECT Cities.id, Cities.`name` FROM Cities WHERE Cities.id = '1efcc4a5-73e6-6e8a-830f-399275c6fb04'
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `foreign key between uuid and entity id column`(testDb: TestDB) {
        withTables(testDb, Cities, Towns) {
            val cId = Cities.insertAndGetId {
                it[name] = "City A"
            }
            val tId = Towns.insertAndGetId {
                it[cityId] = cId.value
            }
            val tId2 = Towns.insertAndGetId {
                it[cityId] = cId.value
            }

            flushCache()

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
