package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.shared.entities.LongIdTables.Cities
import io.bluetape4k.workshop.exposed.domain.shared.entities.LongIdTables.City
import io.bluetape4k.workshop.exposed.domain.shared.entities.LongIdTables.People
import io.bluetape4k.workshop.exposed.domain.shared.entities.LongIdTables.Person
import io.bluetape4k.workshop.exposed.domain.shared.entities.LongIdTables.Town
import io.bluetape4k.workshop.exposed.domain.shared.entities.LongIdTables.Towns
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainSame
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

object LongIdTables {

    object Cities: LongIdTable() {
        val name = varchar("name", 50)
    }

    class City(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<City>(Cities)

        var name: String by Cities.name
        val towns: SizedIterable<Town> by Town referrersOn Towns.cityId
    }

    object People: LongIdTable() {
        val name = varchar("name", 80)
        val cityId = reference("city_id", Cities)
    }

    class Person(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Person>(People)

        var name by People.name
        var city by City referencedOn People.cityId
    }

    object Towns: LongIdTable("towns") {
        val cityId = long("city_id").references(Cities.id)
    }

    class Town(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Town>(Towns)

        var city by City referencedOn Towns.cityId
    }
}


class LongIdTableEntityTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create tables`(testDB: TestDB) {
        withTables(testDB, LongIdTables.Cities, LongIdTables.People) {
            LongIdTables.Cities.exists().shouldBeTrue()
            LongIdTables.People.exists().shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create records`(testDB: TestDB) {
        withTables(testDB, Cities, People) {
            val mumbai = City.new { name = "Mumbai" }
            val pune = City.new { name = "Pune" }

            Person.new {
                name = "David D'souza"
                city = mumbai
            }
            Person.new {
                name = "Tushar Mumbaikar"
                city = mumbai
            }
            Person.new {
                name = "Tanu Arora"
                city = pune
            }

            val allCities = City.all().map { it.name }
            allCities shouldContainSame listOf("Mumbai", "Pune")

            val allPeople = Person.all().map { it.name to it.city.name }
            allPeople shouldContainSame listOf(
                "David D'souza" to "Mumbai",
                "Tushar Mumbaikar" to "Mumbai",
                "Tanu Arora" to "Pune"
            )
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `update and delete records`(testDB: TestDB) {
        withTables(testDB, Cities, People) {
            val mumbai = City.new { name = "Mumbai" }
            val pune = City.new { name = "Pune" }

            Person.new {
                name = "David D'souza"
                city = mumbai
            }
            Person.new {
                name = "Tushar Mumbaikar"
                city = mumbai
            }
            val tanu = Person.new {
                name = "Tanu Arora"
                city = pune
            }

            tanu.delete()
            pune.delete()

            val allCities = City.all().map { it.name }
            allCities shouldContainSame listOf("Mumbai")

            val allPeople = Person.all().map { it.name to it.city.name }
            allPeople shouldContainSame listOf(
                "David D'souza" to "Mumbai",
                "Tushar Mumbaikar" to "Mumbai"
            )
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `foreign key between  long and EntityID columns`(testDB: TestDB) {
        withTables(testDB, Cities, Towns) {
            val cId = Cities.insertAndGetId {
                it[name] = "City A"
            }
            val tId = Towns.insertAndGetId {
                it[cityId] = cId.value
            }
            val tId2 = Towns.insertAndGetId {
                it[cityId] = cId.value
            }

            /**
             * lazy loaded referencedOn
             *
             * ```sql
             * SELECT TOWNS.ID, TOWNS.CITY_ID FROM TOWNS
             * SELECT CITIES.ID, CITIES."name" FROM CITIES WHERE CITIES.ID = 1
             * ```
             */
            val town1 = Town.all().first()
            town1.city.id shouldBeEqualTo cId

            /**
             * eager loaded referrersOn
             *
             * ```sql
             * SELECT TOWNS.ID, TOWNS.CITY_ID FROM TOWNS
             * SELECT CITIES.ID, CITIES."name" FROM CITIES WHERE CITIES.ID = 1
             * SELECT CITIES.ID, CITIES."name" FROM CITIES WHERE CITIES.ID = 1
             * ```
             */
            val town1WithCity = Town.all().with(Town::city).first()
            town1WithCity.city.id shouldBeEqualTo cId

            /**
             * lazy loaded referrersOn
             *
             * ```sql
             * SELECT CITIES.ID, CITIES."name" FROM CITIES
             * SELECT TOWNS.ID, TOWNS.CITY_ID FROM TOWNS WHERE TOWNS.CITY_ID = 1
             * SELECT CITIES.ID, CITIES."name" FROM CITIES WHERE CITIES.ID = 1
             * ```
             */
            val city1 = City.all().single()
            val towns = city1.towns
            towns.first().city.id shouldBeEqualTo cId

            /**
             * eager loaded referrersOn
             *
             * ```sql
             * SELECT CITIES.ID, CITIES."name" FROM CITIES
             * SELECT TOWNS.ID, TOWNS.CITY_ID, CITIES.ID
             *   FROM TOWNS INNER JOIN CITIES ON TOWNS.CITY_ID = CITIES.ID
             *  WHERE TOWNS.CITY_ID = 1
             *
             * SELECT CITIES.ID, CITIES."name" FROM CITIES WHERE CITIES.ID = 1
             * SELECT CITIES.ID, CITIES."name" FROM CITIES WHERE CITIES.ID = 1
             * ```
             */
            val city1WithTowns = City.all().with(City::towns).single()
            city1WithTowns.towns.first().city.id shouldBeEqualTo cId
        }
    }
}
