package io.bluetape4k.workshop.exposed.domain.demo.sql

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.substring
import org.jetbrains.exposed.v1.core.trim
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SamplesSQL: AbstractExposedTest() {

    /**
     * 사용자 정보를 저장하는 테이블
     * ```sql
     * CREATE TABLE IF NOT EXISTS "user" (
     *      ID VARCHAR(10),
     *      "name" VARCHAR(50) NOT NULL,
     *      CITY_ID INT NULL,
     *
     *      CONSTRAINT PK_User_ID PRIMARY KEY (ID),
     *      CONSTRAINT FK_USER_CITY_ID__ID FOREIGN KEY (CITY_ID) REFERENCES CITY(ID)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Users: Table("user") {
        val id = varchar("id", 10)
        val name = varchar("name", length = 50)
        val cityId = integer("city_id").references(Cities.id).nullable()

        override val primaryKey = PrimaryKey(id, name = "PK_User_ID")
    }

    /**
     * 도시 정보를 저장하는 테이블
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS CITY (
     *      ID INT AUTO_INCREMENT,
     *      "name" VARCHAR(50) NOT NULL,
     *
     *      CONSTRAINT PK_Cities_ID PRIMARY KEY (ID)
     * )
     * ```
     */
    object Cities: Table("city") {
        val id = integer("id").autoIncrement()
        val name = varchar("name", 50)

        override val primaryKey = PrimaryKey(id, name = "PK_Cities_ID")
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Raw SQL을 이용하여 DB 작업을 수행합니다`(testDB: TestDB) {
        withTables(testDB, Users, Cities) {

            val seoul = Cities.insert {
                it[name] = "Seoul"
            } get Cities.id

            val busan = Cities.insert {
                it[name] = "Busan"
            } get Cities.id

            // INSERT INTO city ("name") VALUES (SUBSTRING(TRIM('   Daegu   '), 1, 2))
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

            /**
             * ```sql
             * UPDATE "user" SET "name"='Alexey' WHERE "user".ID = 'alex'
             * ```
             */
            Users.update({ Users.id eq "alex" }) {
                it[name] = "Alexey"
            }

            /**
             * ```sql
             * DELETE FROM "user" WHERE "user"."name" LIKE '%thing'
             * ```
             */
            val affectedCount = Users.deleteWhere { name like "%thing" }
            affectedCount shouldBeEqualTo 1

            println("All cities:")
            Cities.selectAll().forEach {
                println("${it[Cities.id]}: ${it[Cities.name]}")
            }

            /**
             * ```sql
             * SELECT "user"."name",
             *        city."name"
             *   FROM "user"
             *      INNER JOIN city ON city.id = "user".city_id
             *  WHERE (("user".id = 'debop') OR ("user"."name" = 'Jarry'))
             *    AND ("user".id = 'jarry')
             *    AND ("user".city_id = city.id)
             * ```
             */
            println("Manual join:")
            Users.innerJoin(Cities)
                .select(Users.name, Cities.name)
                .where { Users.id.eq("debop") or Users.name.eq("Jarry") }
                .andWhere { Users.id eq "jarry" }
                .andWhere { Users.cityId eq Cities.id }
                .forEach {
                    println("${it[Users.name]} lives in ${it[Cities.name]}")
                }

            /**
             * ```sql
             * SELECT "user"."name", "user".city_id, city."name"
             *   FROM "user" INNER JOIN city ON city.id = "user".city_id
             *  WHERE (city."name" = 'Busan')
             *     OR ("user".city_id IS NULL)
             * ```
             */
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

            /**
             * ```sql
             * SELECT city."name",
             *        COUNT("user".id)
             *   FROM city INNER JOIN "user" ON city.id = "user".city_id
             *  GROUP BY city."name"
             */
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
