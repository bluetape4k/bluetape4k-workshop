package io.bluetape4k.workshop.exposed.domain.mapping.subquery

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.asLong
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.PersonSchema.Person
import io.bluetape4k.workshop.exposed.domain.mapping.withPersonsAndAddress
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.sql.CustomOperator
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.longLiteral
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SubqueryTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * SELECT PERSONS.ID,
     *        PERSONS.FIRST_NAME,
     *        PERSONS.LAST_NAME,
     *        PERSONS.BIRTH_DATE,
     *        PERSONS.EMPLOYEED,
     *        PERSONS.OCCUPATION,
     *        PERSONS.ADDRESS_ID
     *   FROM PERSONS
     *  WHERE PERSONS.ID != (SELECT MAX(PERSONS.ID) FROM PERSONS)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select notEqSubQuery`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val query = persons
                .selectAll()
                .where {
                    persons.id notEqSubQuery persons.select(persons.id.max())
                }

            val rows = query.toList()
            rows shouldHaveSize 5

            // Query를 Entity로 만들기
            val personEntities = Person.wrapRows(query).toList()
            personEntities.forEach { person ->
                log.debug { "person: $person" }
            }
        }
    }

    /**
     * ```sql
     * SELECT PERSONS.ID,
     *        PERSONS.FIRST_NAME,
     *        PERSONS.LAST_NAME,
     *        PERSONS.BIRTH_DATE,
     *        PERSONS.EMPLOYEED,
     *        PERSONS.OCCUPATION,
     *        PERSONS.ADDRESS_ID
     *   FROM PERSONS
     *  WHERE PERSONS.ID = (SELECT MAX(PERSONS.ID) FROM PERSONS)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select eqSubQuery`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val query = persons
                .selectAll()
                .where {
                    persons.id eqSubQuery persons.select(persons.id.max())
                }

            val rows = query.toList()
            rows shouldHaveSize 1

            // Query를 Entity로 만들기
            val personEntities = Person.wrapRows(query).toList()
            personEntities.forEach { person ->
                log.debug { "person: $person" }
            }
            personEntities.single().id.value shouldBeEqualTo 6L
        }
    }

    /**
     * ```sql
     * SELECT PERSONS.ID,
     *        PERSONS.FIRST_NAME,
     *        PERSONS.LAST_NAME,
     *        PERSONS.BIRTH_DATE,
     *        PERSONS.EMPLOYEED,
     *        PERSONS.OCCUPATION,
     *        PERSONS.ADDRESS_ID
     *   FROM PERSONS
     *  WHERE PERSONS.ID IN (SELECT PERSONS.ID FROM PERSONS WHERE PERSONS.LAST_NAME = 'Rubble')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select inSubQuery`(testDB: TestDB) {
        withPersonsAndAddress(testDB) { persons, _ ->
            val query = persons
                .selectAll()
                .where {
                    persons.id inSubQuery
                            persons.select(persons.id).where { persons.lastName eq "Rubble" }
                }

            val rows = query.toList()
            rows shouldHaveSize 3

            // Query를 Entity로 만들기
            val entities = Person.wrapRows(query).toList()
            entities.forEach { person ->
                log.debug { "person: $person" }
            }
            entities.all { it.lastName == "Rubble" }.shouldBeTrue()
            entities.map { it.id.value } shouldBeEqualTo listOf(4L, 5L, 6L)
        }
    }

    /**
     * ```sql
     * SELECT PERSONS.ID,
     *        PERSONS.FIRST_NAME,
     *        PERSONS.LAST_NAME,
     *        PERSONS.BIRTH_DATE,
     *        PERSONS.EMPLOYEED,
     *        PERSONS.OCCUPATION,
     *        PERSONS.ADDRESS_ID
     *   FROM PERSONS
     *  WHERE PERSONS.ID NOT IN (SELECT DISTINCT ON (PERSONS.ID) PERSONS.ID
     *                             FROM PERSONS
     *                            WHERE PERSONS.LAST_NAME = 'Rubble')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `select notInSubQuery`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_MYSQL }

        withPersonsAndAddress(testDB) { persons, _ ->
            val query = persons.selectAll()
                .where {
                    persons.id notInSubQuery
                            persons.select(persons.id)
                                .withDistinctOn(persons.id)
                                .where { persons.lastName eq "Rubble" }
                }

            val entities = Person.wrapRows(query).toList()
            entities.forEach { person ->
                log.debug { "person: $person" }
            }
            entities shouldHaveSize 3
            entities.map { it.id.value } shouldBeEqualTo listOf(1L, 2L, 3L)
        }
    }

    //
    // NOTE: lessSubQuery, lessEqSubQuery, greaterSubQuery, greaterEqSubQuery 은 아직 지원하지 않는다
    //


    /**
     * ```sql
     * UPDATE PERSONS
     *    SET ADDRESS_ID=(SELECT (MAX(PERSONS.ADDRESS_ID) - 1) FROM PERSONS)
     *  WHERE PERSONS.ID = 3
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `update set to subquery in H2`(testDB: TestDB) {
        Assumptions.assumeTrue(testDB in TestDB.ALL_H2)

        // implement a +/- operator using CustomOperator
        infix fun Expression<*>.plus(operand: Long) =
            CustomOperator("+", LongColumnType(), this, longLiteral(operand))

        infix fun Expression<*>.minus(operand: Long) =
            CustomOperator("-", LongColumnType(), this, longLiteral(operand))

        withPersonsAndAddress(testDB) { persons, addresses ->
            val affectedRows = persons.update({ persons.id eq 3L }) {
                it[persons.addressId] = persons.select(persons.addressId.max() minus 1L)
            }
            affectedRows shouldBeEqualTo 1

            val person = Person.findById(3L)!!
            person.address.id.value shouldBeEqualTo 1L      // 2 - 1
        }
    }

    /**
     * ```sql
     * SELECT (PERSONS.ADDRESS_ID - 1) addressId
     *   FROM PERSONS
     *  GROUP BY PERSONS.ADDRESS_ID
     *  ORDER BY PERSONS.ADDRESS_ID DESC
     * ```
     * ```sql
     * UPDATE PERSONS SET ADDRESS_ID=1 WHERE PERSONS.ID = 5
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `update set to subquery in MYSQL`(testDB: TestDB) {
        Assumptions.assumeTrue(testDB in TestDB.ALL_MYSQL_LIKE)

        // implement a +/- operator using CustomOperator
        infix fun Expression<*>.plus(operand: Long) =
            CustomOperator("+", LongColumnType(), this, longLiteral(operand))

        infix fun Expression<*>.minus(operand: Long) =
            CustomOperator("-", LongColumnType(), this, longLiteral(operand))

        withPersonsAndAddress(testDB) { persons, addresses ->
            val calcAddressId = (persons.addressId minus 1L).alias("addressId")
            val query = persons.select(calcAddressId)
                .groupBy(persons.addressId)
                .orderBy(persons.addressId, SortOrder.DESC)

            val maxAddressId = query.firstOrNull()?.getOrNull(calcAddressId).asLong()
            log.debug { "maxAddressId=$maxAddressId" }


            val affectedRows = persons.update({ persons.id eq 5L }) {
                it[persons.addressId] = maxAddressId
            }
            affectedRows shouldBeEqualTo 1

            val person = Person.findById(5L)!!
            person.address.id.value shouldBeEqualTo 1L      // 2 - 1
        }
    }
}
