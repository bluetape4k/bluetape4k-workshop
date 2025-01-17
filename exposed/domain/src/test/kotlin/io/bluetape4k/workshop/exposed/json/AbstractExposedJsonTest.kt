package io.bluetape4k.workshop.exposed.json

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import io.bluetape4k.workshop.exposed.json.JsonTestData.JsonArrays
import io.bluetape4k.workshop.exposed.json.JsonTestData.JsonBArrays
import io.bluetape4k.workshop.exposed.json.JsonTestData.JsonBTable
import io.bluetape4k.workshop.exposed.json.JsonTestData.JsonTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.junit.jupiter.api.Assumptions

abstract class AbstractExposedJsonTest: AbstractExposedTest() {

    protected fun withJsonTable(
        testDB: TestDB,
        statement: Transaction.(tester: JsonTable, user1: User, data1: DataHolder) -> Unit,
    ) {
        val tester = JsonTable

        withTables(testDB, tester) {
            val user1 = User("Admin", null)
            val data1 = DataHolder(user1, 10, true, null)

            tester.insert { it[JsonTable.jsonColumn] = data1 }

            statement(tester, user1, data1)
        }
    }

    protected fun withJsonBTable(
        testDB: TestDB,
        statement: Transaction.(tester: JsonBTable, user1: User, data1: DataHolder) -> Unit,
    ) {
        val tester = JsonBTable

        withTables(testDB, tester) {
            val user1 = User("Admin", null)
            val data1 = DataHolder(user1, 10, true, null)

            tester.insert { it[JsonBTable.jsonBColumn] = data1 }

            statement(tester, user1, data1)
        }
    }

    protected fun withJsonArrays(
        testDB: TestDB,
        statement: Transaction.(
            tester: JsonArrays,
            singleId: EntityID<Int>,
            tripleId: EntityID<Int>,
        ) -> Unit,
    ) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2 }

        val tester = JsonArrays

        withTables(testDB, tester) {
            val singleId = tester.insertAndGetId {
                it[tester.groups] = UserGroup(listOf(User("Admin", "Team A")))
                it[tester.numbers] = intArrayOf(100)
            }
            val tripleId = tester.insertAndGetId {
                // User name = "B", "C", "D"
                // User team = "Team B", "Team C", "Team D"
                it[tester.groups] = UserGroup(List(3) { i -> User("${'B' + i}", "Team ${'B' + i}") })
                it[numbers] = intArrayOf(3, 4, 5)
            }

            statement(tester, singleId, tripleId)
        }
    }

    protected fun withJsonBArrays(
        testDB: TestDB,
        statement: Transaction.(
            tester: JsonBArrays,
            singleId: EntityID<Int>,
            tripleId: EntityID<Int>,
        ) -> Unit,
    ) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2 }

        val tester = JsonBArrays

        withTables(testDB, tester) {
            val singleId = tester.insertAndGetId {
                it[tester.groups] = UserGroup(listOf(User("Admin", "Team A")))
                it[tester.numbers] = intArrayOf(100)
            }
            val tripleId = tester.insertAndGetId {
                it[tester.groups] = UserGroup(List(3) { i -> User("${'B' + i}", "Team ${'B' + i}") })
                it[tester.numbers] = intArrayOf(3, 4, 5)
            }

            statement(tester, singleId, tripleId)
        }
    }
}
