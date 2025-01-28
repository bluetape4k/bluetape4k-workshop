package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Join
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.JoinType.LEFT
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.batchInsert
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
     * SELECT parent.id,
     *        parent."value",
     *        q0.id,
     *        q0.parent_id,
     *        q0."value"
     *   FROM parent CROSS JOIN LATERAL
     *          (SELECT child.id,
     *                  child.parent_id,
     *                  child."value"
     *             FROM child
     *            WHERE child."value" > parent."value"
     *            LIMIT 1
     *          ) q0
     * ```
     */
    @ParameterizedTest
    @FieldSource("lateralJoinSupportedDb")
    fun `lateral join query`(testDB: TestDB) {
        withTestTablesAndDefaultData(testDB) { parent, child, _ ->

            val query = parent.joinQuery(joinType = JoinType.CROSS, lateral = true) {
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

            /**
             * Cross Join Lateral Query
             *
             * ```sql
             * SELECT parent.id,
             *        parent."value",
             *        child1.id,
             *        child1.parent_id,
             *        child1."value"
             *   FROM parent CROSS JOIN LATERAL
             *          (SELECT child.id,
             *                  child.parent_id,
             *                  child."value"
             *             FROM child
             *            WHERE child."value" > parent."value"
             *            LIMIT 1
             *          ) child1
             * ```
             */
            child.selectAll()
                .where { child.value greater parent.value }
                .limit(1)
                .alias("child1")
                .let { subqueryAlias ->
                    val join = parent.join(
                        subqueryAlias,
                        JoinType.CROSS,
                        onColumn = parent.id,
                        otherColumn = subqueryAlias[child.parent],
                        lateral = true
                    )

                    join.selectAll().map { it[subqueryAlias[child.value]] } shouldBeEqualTo listOf(30)
                }

            /**
             * Left Join Lateral Query
             *
             * ```sql
             * SELECT parent.id,
             *        parent."value",
             *        child1.id,
             *        child1.parent_id,
             *        child1."value"
             *   FROM parent LEFT JOIN LATERAL
             *          (SELECT child.id,
             *                  child.parent_id,
             *                  child."value"
             *             FROM child
             *            WHERE child."value" > parent."value"
             *          ) child1 ON parent.id = child1.parent_id
             * ```
             */
            child.selectAll()
                .where { child.value greater parent.value }
                .alias("child1")
                .let { subqueryAlias ->
                    val join = parent.join(
                        subqueryAlias,
                        JoinType.LEFT,
                        onColumn = parent.id,
                        otherColumn = subqueryAlias[child.parent],
                        lateral = true
                    )

                    join.selectAll().map { it[subqueryAlias[child.value]] } shouldBeEqualTo listOf(30)
                }

            /**
             * Left join to Alias
             *
             * ```sql
             * SELECT parent1.id,
             *        parent1."value",
             *        child1.id,
             *        child1.parent_id,
             *        child1."value"
             *   FROM (SELECT parent.id,
             *                parent."value"
             *           FROM parent) parent1
             *         LEFT JOIN LATERAL
             *         (SELECT child.id,
             *                 child.parent_id,
             *                 child."value"
             *            FROM child
             *           WHERE child."value" > parent1."value") child1
             *         ON parent1.id = child1.parent_id
             * ```
             */
            val parentQuery = parent.selectAll().alias("parent1")
            child.selectAll()
                .where { child.value greater parentQuery[parent.value] }
                .alias("child1")
                .let { subqueryAlias ->
                    val join: Join = parentQuery.join(
                        subqueryAlias,
                        JoinType.LEFT,
                        onColumn = parentQuery[parent.id],
                        otherColumn = subqueryAlias[child.parent],
                        lateral = true
                    )

                    join.selectAll().map { it[subqueryAlias[child.value]] } shouldBeEqualTo listOf(30)
                }
        }
    }

    @ParameterizedTest
    @FieldSource("lateralJoinSupportedDb")
    fun `lateral direct table join`(dialect: TestDB) {
        withTestTables(dialect) { parent, child, _ ->
            // Lateral 적용 시, 명시적으로 테이블 간의 JOIN 조건의 컬럼을 지정하면 예외가 발생합니다. (쿼리를 사용해야 합니다)
            expectException<IllegalArgumentException> {
                parent.join(child, LEFT, onColumn = parent.id, otherColumn = child.parent, lateral = true)
            }

            // Lateral 적용 시, 암묵적인 테이블 간의 JOIN 조건의 컬럼을 지정하면 예외가 발생합니다. (쿼리를 사용해야 합니다)
            expectException<IllegalArgumentException> {
                parent.join(child, LEFT, lateral = true).selectAll().toList()
            }
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS parent (
     *      id SERIAL PRIMARY KEY,
     *      "value" INT NOT NULL
     * )
     * ```
     */
    object Parent: IntIdTable("parent") {
        val value = integer("value")
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS child (
     *      id SERIAL PRIMARY KEY,
     *      parent_id INT NOT NULL,
     *      "value" INT NOT NULL,
     *
     *      CONSTRAINT fk_child_parent_id__id
     *          FOREIGN KEY (parent_id) REFERENCES parent(id)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Child: IntIdTable("child") {
        val parent = reference("parent_id", Parent.id)
        val value = integer("value")
    }

    private fun withTestTables(dialect: TestDB, statement: Transaction.(Parent, Child, TestDB) -> Unit) {
        withTables(dialect, Parent, Child) { testDb ->
            statement(Parent, Child, testDb)
        }
    }

    private fun withTestTablesAndDefaultData(
        testDB: TestDB,
        statement: Transaction.(Parent, Child, TestDB) -> Unit,
    ) {
        withTestTables(testDB) { parent, child, testDb ->
            val id = parent.insertAndGetId { it[value] = 20 }

            child.batchInsert(listOf(10, 30)) { value ->
                this[child.parent] = id
                this[child.value] = value
            }

            statement(parent, child, testDb)
        }
    }

}
