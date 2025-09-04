package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.notLike
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.upperCase
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class WhereConditionsTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS USERS ("name" VARCHAR(20) NOT NULL)
     * ```
     */
    object Users: Table() {
        val name = varchar("name", 20)
    }

    /**
     * ```sql
     * SELECT USERS."name"
     *   FROM USERS
     *  WHERE USERS."name" LIKE UPPER('Bost%')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `where like expression`(testDB: TestDB) {
        withTables(testDB, Users) {
            Users.insert {
                it[name] = "BOSTON"
            }
            Users.insert {
                it[name] = "SEOUL"
            }
            val namesResult: List<String> = Users.selectAll()
                .where {
                    Users.name like stringLiteral("Bost%").upperCase()
                }
                .map { it[Users.name] }

            namesResult shouldHaveSize 1
            namesResult.single() shouldBeEqualTo "BOSTON"
        }
    }

    /**
     * ```sql
     * SELECT USERS."name"
     *   FROM USERS
     *  WHERE USERS."name" NOT LIKE UPPER('Bost%')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `where not like expression`(testDB: TestDB) {
        withTables(testDB, Users) {
            Users.insert {
                it[name] = "BOSTON"
            }
            Users.insert {
                it[name] = "SEOUL"
            }
            val namesResult: List<String> = Users.selectAll()
                .where {
                    Users.name notLike stringLiteral("Bost%").upperCase()
                }
                .map { it[Users.name] }

            namesResult shouldHaveSize 1
            namesResult.single() shouldBeEqualTo "SEOUL"
        }
    }
}
