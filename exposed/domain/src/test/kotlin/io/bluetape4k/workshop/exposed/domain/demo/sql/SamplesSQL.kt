package io.bluetape4k.workshop.exposed.domain.demo.sql

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.substring
import org.jetbrains.exposed.sql.trim
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SamplesSQL: AbstractExposedTest() {

    object Users: Table("user") {
        val id = varchar("id", 10)
        val name = varchar("name", length = 50)
        val cityId = (integer("city_id") references Cities.id).nullable()

        override val primaryKey = PrimaryKey(id, name = "PK_User_ID")
    }

    object Cities: Table("city") {
        val id = integer("id").autoIncrement()
        val name = varchar("name", 50)

        override val primaryKey = PrimaryKey(id, name = "PK_Cities_ID")
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Raw SQL을 이용하여 DB 작업을 수행합니다`(dialect: TestDB) {
        withTables(dialect, Users, Cities) {

            val seoul = Cities.insert {
                it[name] = "Seoul"
            } get Cities.id

            val busan = Cities.insert {
                it[name] = "Busan"
            } get Cities.id

            val daegu = Cities.insert {
                it.update(name, stringLiteral("   Daegu   ").trim().substring(1, 2))
            }[Cities.id]

            val daeguName = Cities.selectAll().where { Cities.id eq daegu }.single()[Cities.name]
            daeguName shouldBeEqualTo "Da"

            Users.insert {
                it[id] = "debop"
                it[name] = "Debop.Bae"
                it[cityId] = seoul
            }

            Users.insert {
                it[id] = "jarry"
                it[name] = "Jarry"
                it[cityId] = busan
            }

            Users.insert {
                it[id] = "needs"
                it[name] = "Needs"
                it[cityId] = busan
            }

            Users.insert {
                it[id] = "alex"
                it[name] = "Alex"
                it[cityId] = null
            }

            Users.insert {
                it[id] = "smth"
                it[name] = "Something"
                it[cityId] = null
            }

            Users.update({ Users.id eq "alex" }) {
                it[name] = "Alexey"
            }

            Users.deleteWhere { name like "%thing" }

            println("All cities:")
            Cities.selectAll().forEach {
                println("${it[Cities.id]}: ${it[Cities.name]}")
            }

            println("Manual join:")
            Users.innerJoin(Cities)
                .select(Users.name, Cities.name)
                .where {
                    (Users.id.eq("debop") or Users.name.eq("Jarry")) and
                            Users.id.eq("jarry") and
                            Users.cityId.eq(Cities.id)
                }
                .forEach {
                    println("${it[Users.name]} lives in ${it[Cities.name]}")
                }

            println("Join with froeign key:")

            Users.innerJoin(Cities)
                .select(Users.name, Users.cityId, Cities.name)
                .where { Cities.name.eq("Busan") or Users.cityId.isNull() }
                .forEach {
                    if (it[Users.cityId] != null) {
                        println("${it[Users.name]} lives in ${it[Cities.name]}")
                    } else {
                        println("${it[Users.name]} lives nowhere")
                    }
                }

            println("Functions and group by:")

            val query = Cities.innerJoin(Users)
                .select(Cities.name, Users.id.count())
                .groupBy(Cities.name)

            query.forEach {
                val cityName = it[Cities.name]
                val userCount = it[Users.id.count()]

                if (userCount > 0) {
                    println("$cityName 에는 $userCount 명의 사용자가 살고 있습니다.")
                } else {
                    println("$cityName 에는 사용자가 없습니다.")
                }
            }
        }
    }
}
