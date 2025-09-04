package io.bluetape4k.workshop.exposed.domain.demo.dao

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.SizedIterable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SamplesDao: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * 사용자 정보를 저장하는 테이블
     * ```sql
     * CREATE TABLE IF NOT EXISTS USERS (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "name" VARCHAR(50) NOT NULL,
     *      AGE INT NOT NULL,
     *      CITY_ID INT NULL,
     *
     *      CONSTRAINT FK_USERS_CITY_ID__ID FOREIGN KEY (CITY_ID) REFERENCES CITIES(ID)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Users: IntIdTable() {
        val name: Column<String> = varchar("name", 50).index()
        val age: Column<Int> = integer("age")
        val city: Column<EntityID<Int>?> = optReference("city_id", Cities)
    }

    /**
     * 도시 정보를 저장하는 테이블
     * ```sql
     * CREATE TABLE IF NOT EXISTS CITIES (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "name" VARCHAR(50) NOT NULL
     * )
     * ```
     */
    object Cities: IntIdTable() {
        val name: Column<String> = varchar("name", 50)
    }

    class User(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<User>(Users)

        var name: String by Users.name
        var age: Int by Users.age
        var city: City? by City optionalReferencedOn Users.city

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = toStringBuilder()
            .add("name", name)
            .add("age", age)
            .add("city", city)
            .toString()
    }

    class City(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<City>(Cities)

        var name: String by Cities.name
        val users: SizedIterable<User> by User optionalReferrersOn Users.city

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = toStringBuilder()
            .add("name", name)
            .toString()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `dao entity - one to many`(testDB: TestDB) {
        withTables(testDB, Users, Cities) {
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

            entityCache.clear()

            City.all().toList() shouldBeEqualTo listOf(seoul, busan)
            City.findById(seoul.id) shouldBeEqualTo seoul

            val usersInSeoul = seoul.users.toList()
            usersInSeoul shouldBeEqualTo listOf(a, b)

            val users = User.find { Users.age greaterEq 18 }.toList()
            users shouldBeEqualTo listOf(b, c)
        }
    }
}
