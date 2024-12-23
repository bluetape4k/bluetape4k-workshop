package io.bluetape4k.workshop.exposed.domain.demo.dao

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedDomainTest
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.junit.jupiter.api.Test

class SamplesDao: AbstractExposedDomainTest() {

    companion object: KLogging()

    object Users: IntIdTable() {
        val name = varchar("name", 50).index()
        val age = integer("age")
        val city = reference("city_id", Cities)
    }

    object Cities: IntIdTable() {
        val name = varchar("name", 50)
    }

    class User(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<User>(Users)

        var name by Users.name
        var age by Users.age
        var city by City referencedOn Users.city
    }

    class City(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<City>(Cities)

        var name by Cities.name
        val users by User referrersOn Users.city
    }

    @Test
    fun `dao entity - one to many`() {
        withTables(Users, Cities) {
            val seoul = City.new {
                name = "Seoul"
            }

            val busan = City.new {
                name = "Busan"
            }

            val a = User.new {
                name = "a"
                age = 5
                city = seoul
            }

            val b = User.new {
                name = "b"
                age = 27
                city = seoul
            }

            val c = User.new {
                name = "c"
                age = 42
                city = busan
            }

            City.all().toList() shouldBeEqualTo listOf(seoul, busan)

            City.findById(seoul.id) shouldBeEqualTo seoul

            val usersInSeoul = seoul.users.toList()
            usersInSeoul shouldBeEqualTo listOf(a, b)

            val users = User.find { Users.age greaterEq 18 }.toList()
            users shouldBeEqualTo listOf(b, c)
        }

    }
}
