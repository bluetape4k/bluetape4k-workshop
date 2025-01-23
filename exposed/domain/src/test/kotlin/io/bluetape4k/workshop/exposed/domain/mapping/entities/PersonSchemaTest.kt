package io.bluetape4k.workshop.exposed.domain.mapping.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.PersonSchema
import io.bluetape4k.workshop.exposed.domain.mapping.PersonSchema.Address
import io.bluetape4k.workshop.exposed.domain.mapping.PersonSchema.Person
import io.bluetape4k.workshop.exposed.domain.mapping.PersonSchema.PersonRecord
import io.bluetape4k.workshop.exposed.domain.mapping.withPersonsAndAddress
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate

class PersonSchemaTest: AbstractExposedTest() {
    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count with where clause`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, addresses ->
            // SELECT COUNT(*) FROM PERSONS WHERE PERSONS.ID < 3
            persons.selectAll()
                .where { persons.id less 3L }
                .count() shouldBeEqualTo 2L

            // SELECT COUNT(*) FROM PERSONS WHERE PERSONS.ID < 3
            Person.find { persons.id less 3L }.count() shouldBeEqualTo 2L

            // SELECT COUNT(PERSONS.ID) FROM PERSONS WHERE PERSONS.ID < 3
            persons.select(persons.id.count())
                .where { persons.id less 3L }
                .single()[persons.id.count()] shouldBeEqualTo 2L

            // SELECT COUNT(PERSONS.ID) FROM PERSONS WHERE PERSONS.ID < 3
            Person.count(persons.id less 3L) shouldBeEqualTo 2L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count all records`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, addresses ->

            // SQL 이용
            persons.selectAll().count() shouldBeEqualTo 6L

            // DAO 이용 
            Person.all().count() shouldBeEqualTo 6L
        }
    }

    /**
     * ```sql
     * SELECT COUNT(PERSONS.LAST_NAME) FROM PERSONS
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count lastName`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            persons.select(persons.lastName.count())
                .single()[persons.lastName.count()] shouldBeEqualTo 6L
        }
    }

    /**
     * ```sql
     * SELECT COUNT(DISTINCT PERSONS.LAST_NAME) FROM PERSONS
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `count distinct lastName`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val counter = persons.lastName.countDistinct()

            persons
                .select(counter)
                .single()[counter] shouldBeEqualTo 2L

        }
    }

    private fun Transaction.insertPerson(): Long {
        return PersonSchema.PersonTable.insertAndGetId {
            it[firstName] = faker.name().firstName()
            it[lastName] = faker.name().lastName()
            it[birthDate] = java.time.LocalDate.now()
            it[addressId] = 1L
        }.value
    }

    /**
     * ```sql
     * DELETE FROM PERSONS WHERE PERSONS.ID = 7
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete by id`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val pId = insertPerson()

            // DELETE FROM PERSONS WHERE PERSONS.ID = 7
            persons.deleteWhere { persons.id eq pId } shouldBeEqualTo 1
        }
    }

    /**
     * ```sql
     * DELETE FROM PERSONS WHERE (PERSONS.ID > $id1) AND (PERSONS.OCCUPATION IS NULL)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete by where and`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val id1 = insertPerson()
            val id2 = insertPerson()
            val id3 = insertPerson()

            // DELETE FROM PERSONS WHERE (PERSONS.ID > $id1) AND (PERSONS.OCCUPATION IS NULL)
            persons
                .deleteWhere {
                    persons.id greaterEq id1 and persons.occupation.isNull()
                } shouldBeEqualTo 3
        }
    }

    /**
     * ```sql
     * DELETE FROM PERSONS
     *  WHERE (PERSONS.ID > $id1) OR (PERSONS.OCCUPATION IS NOT NULL)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete by where or`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val id1 = insertPerson()
            val id2 = insertPerson()
            val id3 = insertPerson()

            // DELETE FROM PERSONS WHERE (PERSONS.ID > $id1) OR (PERSONS.OCCUPATION IS NOT NULL)
            persons
                .deleteWhere {
                    persons.id greaterEq id1 or persons.occupation.isNotNull()
                } shouldBeEqualTo 7
        }
    }

    /**
     * ```sql
     * DELETE FROM PERSONS
     *  WHERE ((PERSONS.ID >= 7) OR (PERSONS.OCCUPATION IS NOT NULL))
     *    AND (PERSONS.EMPLOYEED = TRUE)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete by where or and`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val id1 = insertPerson()
            val id2 = insertPerson()
            val id3 = insertPerson()

            // DELETE FROM PERSONS WHERE (PERSONS.ID > $id1) OR (PERSONS.OCCUPATION IS NOT NULL) AND (PERSONS.ID < $id3)
            persons
                .deleteWhere {
                    (persons.id greaterEq id1 or persons.occupation.isNotNull()) and (persons.employeed eq true)
                } shouldBeEqualTo 5
        }
    }

    /**
     * ```sql
     * DELETE FROM PERSONS
     *  WHERE (PERSONS.ID >= 7)
     *     OR ((PERSONS.OCCUPATION IS NOT NULL) AND (PERSONS.EMPLOYEED = TRUE))
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete by where and or`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val id1 = insertPerson()
            val id2 = insertPerson()
            val id3 = insertPerson()


            persons
                .deleteWhere {
                    (persons.id greaterEq id1) or ((persons.occupation.isNotNull()) and (persons.employeed eq true))
                } shouldBeEqualTo 5
        }
    }

    /**
     * ```sql
     * DELETE FROM PERSONS WHERE PERSONS.ID < 7 LIMIT 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete by where with limit`(testDB: TestDB) {
        // PostgreSQL doesn't support LIMIT in DELETE clause
        Assumptions.assumeTrue { testDB !in TestDB.ALL_POSTGRES_LIKE }

        withPersonsAndAddress(testDB) { persons, _ ->
            val id1 = insertPerson()
            val id2 = insertPerson()
            val id3 = insertPerson()

            // DELETE FROM PERSONS WHERE PERSONS.ID < $id1 LIMIT 1
            persons
                .deleteWhere(limit = 1)
                {
                    persons.id less id1
                } shouldBeEqualTo 1
        }
    }

    /**
     * ```sql
     * INSERT INTO PERSONS (ID, FIRST_NAME, LAST_NAME, BIRTH_DATE, OCCUPATION, ADDRESS_ID)
     * VALUES (100, 'John', 'Doe', '2025-01-23', 'Software Engineer', 1)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert entity`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val person = Person.new(100) {
                firstName = "John"
                lastName = "Doe"
                birthDate = java.time.LocalDate.now()
                employeed = true
                occupation = "Software Engineer"
                address = Address[1L]
            }

            entityCache.clear()

            val saved = Person.findById(person.id)!!
            saved shouldBeEqualTo person
        }
    }

    /**
     * ```sql
     * INSERT INTO PERSONS (FIRST_NAME, LAST_NAME, BIRTH_DATE, EMPLOYEED, OCCUPATION, ADDRESS_ID)
     * VALUES ('John', 'Doe', '2025-01-23', TRUE, 'Software Engineer', 1)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert record`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val personId = persons.insertAndGetId {
                it[firstName] = "John"
                it[lastName] = "Doe"
                it[birthDate] = java.time.LocalDate.now()
                it[employeed] = true
                it[occupation] = "Software Engineer"
                it[addressId] = 1L
            }

            val saved = persons.selectAll().where { persons.id eq personId }.single()
            saved[persons.id] shouldBeEqualTo personId
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batchInsert 01`(testDB: TestDB) {

        withPersonsAndAddress(testDB) { persons, _ ->
            val record1 = PersonRecord(null, "Joe", "Jones", LocalDate.now(), true, "Developer", 1L)
            val record2 = PersonRecord(null, "Sarah", "Smith", LocalDate.now(), true, "Architect", 2L)

            val rows = persons.batchInsert(listOf(record1, record2)) { record ->
                this[persons.firstName] = record.firstName!!
                this[persons.lastName] = record.lastName!!
                this[persons.birthDate] = record.birthDate!!
                this[persons.employeed] = record.employeed!!
                this[persons.occupation] = record.occupation
                this[persons.addressId] = record.address!!
            }

            rows shouldHaveSize 2
            rows.all { it[persons.id].value > 0 }.shouldBeTrue()
        }
    }

    /**
     * ```sql
     * INSERT INTO PERSONS (FIRST_NAME, LAST_NAME, BIRTH_DATE, EMPLOYEED, OCCUPATION, ADDRESS_ID)
     * SELECT PERSONS.FIRST_NAME,
     *        PERSONS.LAST_NAME,
     *        PERSONS.BIRTH_DATE,
     *        PERSONS.EMPLOYEED,
     *        PERSONS.OCCUPATION,
     *        PERSONS.ADDRESS_ID
     *   FROM PERSONS
     *  ORDER BY PERSONS.ID ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert select example 01`(testDb: TestDB) {
        withPersonsAndAddress(testDb) { persons, _ ->
            persons.selectAll().count() shouldBeEqualTo 6L

            val inserted = persons.insert(
                persons.select(
                    persons.firstName,
                    persons.lastName,
                    persons.birthDate,
                    persons.employeed,
                    persons.occupation,
                    persons.addressId
                )
                    .where {
                        val occupation: String? = null
                        occupation?.let { persons.occupation.like("%$occupation%") } ?: Op.TRUE
                    }
                    .orderBy(persons.id)
            )

            inserted shouldBeEqualTo 6
            persons.selectAll().count() shouldBeEqualTo 12L
        }
    }

    /**
     * ```sql
     * INSERT INTO PERSONS (ID, FIRST_NAME, LAST_NAME, BIRTH_DATE, EMPLOYEED, OCCUPATION, ADDRESS_ID)
     * SELECT (PERSONS.ID + 100),
     *        PERSONS.FIRST_NAME,
     *        PERSONS.LAST_NAME,
     *        PERSONS.BIRTH_DATE,
     *        PERSONS.EMPLOYEED,
     *        PERSONS.OCCUPATION,
     *        PERSONS.ADDRESS_ID
     *   FROM PERSONS
     *  ORDER BY PERSONS.ID ASC
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert select example 02`(testDb: TestDB) {
        withPersonsAndAddress(testDb) { persons, _ ->
            persons.selectAll().count() shouldBeEqualTo 6L

            // PersonTaleDML 은 PersonTable과 같은 테이블을 사용하지만,
            // id에 AutoIncrement 를 지정하지 않아, 이렇게 id를 직접 지정할 수 있습니다.
            val personsDml = PersonSchema.PersonTableDML

            val inserted = personsDml.insert(
                personsDml.select(
                    personsDml.id + 100L,
                    personsDml.firstName,
                    personsDml.lastName,
                    personsDml.birthDate,
                    personsDml.employeed,
                    personsDml.occupation,
                    personsDml.addressId
                )
                    .orderBy(personsDml.id)
            )

            inserted shouldBeEqualTo 6
            persons.selectAll().count() shouldBeEqualTo 12L
        }
    }
}
