package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.JoinType.LEFT
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.joinQuery
import org.jetbrains.exposed.sql.lastQueryAlias
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

class LateralJoinTest: AbstractExposedTest() {

    private val lateralJoinSupportedDb = listOf(TestDB.POSTGRESQL)

    /**
     * ### Lateral Join Query
     *
     * ```sql
     * SELECT literal_join_parent.id, literal_join_parent."value", q0.id, q0.tester1, q0."value"
     *   FROM literal_join_parent
     *   CROSS JOIN LATERAL (SELECT literal_join_child.id, literal_join_child.tester1, literal_join_child."value"
     *                         FROM literal_join_child
     *                        WHERE literal_join_child."value" > literal_join_parent."value" LIMIT 1
     *                      ) q0
     * ```
     */
    @ParameterizedTest
    @FieldSource("lateralJoinSupportedDb")
    fun `lateral join query`(dialect: TestDB) {
        withTestTablesAndDefaultData(dialect) { parent, child, _ ->
            val query = parent.joinQuery(
                joinType = JoinType.CROSS,
                lateral = true
            ) {
                child.selectAll().where { child.value greater parent.value }.limit(1)
            }

            val subqueryAlias = query.lastQueryAlias ?: error("Alias must exist!")

            query.selectAll().map { it[subqueryAlias[child.value]] } shouldBeEqualTo listOf(30)
        }
    }

    @ParameterizedTest
    @FieldSource("lateralJoinSupportedDb")
    fun `lateral join query alias`(dialect: TestDB) {
        withTestTablesAndDefaultData(dialect) { parent, child, _ ->
            // Cross join
            child.selectAll().where { child.value greater parent.value }.limit(1).alias("subquery")
                .let { subqueryAlias ->
                    val query = parent.join(
                        subqueryAlias,
                        JoinType.CROSS,
                        onColumn = parent.id,
                        otherColumn = subqueryAlias[child.parent],
                        lateral = true
                    )

                    query.selectAll().map { it[subqueryAlias[child.value]] } shouldBeEqualTo listOf(30)
                }

            // Left join
            child.selectAll().where { child.value greater parent.value }.alias("subquery")
                .let { subqueryAlias ->
                    val query = parent.join(
                        subqueryAlias,
                        JoinType.LEFT,
                        onColumn = parent.id,
                        otherColumn = subqueryAlias[child.parent],
                        lateral = true
                    )

                    query.selectAll().map { it[subqueryAlias[child.value]] } shouldBeEqualTo listOf(30)
                }

            // Left join to Alias
            val parentQuery = parent.selectAll().alias("parent_query")
            child.selectAll().where { child.value greater parentQuery[parent.value] }.alias("subquery")
                .let { subqueryAlias ->
                    val query = parentQuery.join(
                        subqueryAlias,
                        JoinType.LEFT,
                        onColumn = parentQuery[parent.id],
                        otherColumn = subqueryAlias[child.parent],
                        lateral = true
                    )

                    query.selectAll().map { it[subqueryAlias[child.value]] } shouldBeEqualTo listOf(30)
                }
        }
    }

    @ParameterizedTest
    @FieldSource("lateralJoinSupportedDb")
    fun `lateral direct table join`(dialect: TestDB) {
        withTestTables(dialect) { parent, child, _ ->
            // Explicit notation
            expectException<IllegalArgumentException> {
                parent.join(child, LEFT, onColumn = parent.id, otherColumn = child.parent, lateral = true)
            }

            // Implicit notation
            expectException<IllegalArgumentException> {
                parent.join(child, LEFT, lateral = true).selectAll().toList()
            }
        }
    }


    object Parent: IntIdTable("literal_join_parent") {
        val value = integer("value")
    }

    object Child: IntIdTable("literal_join_child") {
        val parent = reference("tester1", Parent.id)
        val value = integer("value")
    }

    private fun withTestTables(dialect: TestDB, statement: Transaction.(Parent, Child, TestDB) -> Unit) {
        withTables(dialect, Parent, Child) { testDb ->
            statement(Parent, Child, testDb)
        }
    }

    private fun withTestTablesAndDefaultData(
        dialect: TestDB,
        statement: Transaction.(Parent, Child, TestDB) -> Unit,
    ) {
        withTestTables(dialect) { parent, child, testDb ->
            val id = parent.insertAndGetId { it[value] = 20 }

            listOf(10, 30).forEach { value ->
                child.insert {
                    it[child.parent] = id
                    it[child.value] = value
                }
            }

            statement(parent, child, testDb)
        }
    }

}
