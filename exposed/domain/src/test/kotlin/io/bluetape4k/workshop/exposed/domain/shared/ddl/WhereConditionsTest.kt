package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.upperCase
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class WhereConditionsTest: AbstractExposedTest() {

    companion object: KLogging()

    object Users: Table() {
        val name = varchar("name", 20)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `where like expression`(testDb: TestDB) {
        withTables(testDb, Users) {
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `where not like expression`(testDb: TestDB) {
        withTables(testDb, Users) {
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
