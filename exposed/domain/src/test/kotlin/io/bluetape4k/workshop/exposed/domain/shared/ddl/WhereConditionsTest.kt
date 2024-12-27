package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.upperCase
import org.junit.jupiter.api.Test

class WhereConditionsTest: AbstractExposedTest() {

    object Users: Table() {
        val name = varchar("name", 20)
    }

    @Test
    fun `where like expression`() {
        withTables(Users) {
            Users.insert {
                it[name] = "HICHEM"
            }
            Users.insert {
                it[name] = "SEOUL"
            }
            val namesResult: List<String> = Users.selectAll()
                .where {
                    Users.name like stringLiteral("Hich%").upperCase()
                }
                .map { it[Users.name] }

            namesResult shouldHaveSize 1
            namesResult.first() shouldBeEqualTo "HICHEM"
        }
    }

    @Test
    fun `where not like expression`() {
        withTables(Users) {
            Users.insert {
                it[name] = "HICHEM"
            }
            Users.insert {
                it[name] = "SEOUL"
            }
            val namesResult: List<String> = Users.selectAll()
                .where {
                    Users.name notLike stringLiteral("Hich%").upperCase()
                }
                .map { it[Users.name] }

            namesResult shouldHaveSize 1
            namesResult.first() shouldBeEqualTo "SEOUL"
        }
    }
}
