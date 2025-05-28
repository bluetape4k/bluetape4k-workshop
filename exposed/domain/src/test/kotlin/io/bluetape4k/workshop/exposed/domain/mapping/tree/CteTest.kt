//package io.bluetape4k.workshop.exposed.domain.mapping.tree
//
//import io.bluetape4k.logging.KLogging
//import io.bluetape4k.logging.debug
//import io.bluetape4k.workshop.exposed.AbstractExposedTest
//import io.bluetape4k.workshop.exposed.TestDB
//import io.bluetape4k.workshop.exposed.withTables
//import org.amshove.kluent.shouldHaveSize
//import org.jetbrains.exposed.v1.core.IColumnType
//import org.jetbrains.exposed.v1.core.statements.Statement
//import org.jetbrains.exposed.v1.core.statements.StatementType
//import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
//import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
//import org.junit.jupiter.api.Assumptions
//import org.junit.jupiter.params.ParameterizedTest
//import org.junit.jupiter.params.provider.MethodSource
//import java.io.Serializable
//import java.sql.ResultSet
//
//class CteTest: AbstractExposedTest() {
//
//    companion object: KLogging()
//
//    private val stmt =
//        """
//        WITH RECURSIVE cte AS (
//            SELECT id, title, parent_id, depth, id::TEXT as path
//              FROM tree_nodes
//             WHERE parent_id IS NULL
//
//            UNION ALL
//
//            SELECT tn.id, tn.title, tn.parent_id, tn.depth, (cte.path || '.' || tn.id::TEXT) as path
//              FROM tree_nodes tn JOIN cte ON tn.parent_id = cte.id
//        )
//        SELECT id, title, parent_id, depth, path FROM cte
//        """.trimIndent()
//
//    @ParameterizedTest
//    @MethodSource(ENABLE_DIALECTS_METHOD)
//    fun `raw common table expression for treeNode`(testDB: TestDB) {
//        Assumptions.assumeTrue { testDB in TestDB.ALL_POSTGRES }
//
//        withTables(testDB, TreeNodeTable) {
//            buildTreeNodes()
//
//            exec(stmt, explicitStatementType = StatementType.SELECT) { rs ->
//                while (rs.next()) {
//                    val id = rs.getLong("id")
//                    val title = rs.getString("title")
//                    val parentId = rs.getLong("parent_id")
//                    val depth = rs.getInt("depth")
//                    val path = rs.getString("path")
//                    log.debug { "id=$id, title=$title, parent_id=$parentId, depth=$depth, path=$path" }
//                }
//            }
//        }
//    }
//
//    @ParameterizedTest
//    @MethodSource(ENABLE_DIALECTS_METHOD)
//    fun `raw common table expression to treeNodeRecord`(testDB: TestDB) {
//        Assumptions.assumeTrue { testDB in TestDB.ALL_POSTGRES }
//
//        withTables(testDB, TreeNodeTable) {
//            buildTreeNodes()
//
//            val records = execCTE(stmt) {
//                TreeNodeRecord(
//                    it.getLong("id"),
//                    it.getString("title"),
//                    it.getLong("parent_id"),
//                    it.getInt("depth"),
//                    it.getString("path"),
//                )
//            }
//
//            records shouldHaveSize 5
//
//            records.forEach {
//                log.debug { "id=${it.id}, title=${it.title}, parent_id=${it.parentId}, depth=${it.depth}, path=${it.path}" }
//            }
//        }
//    }
//
//    data class TreeNodeRecord(
//        val id: Long,
//        val title: String,
//        val parentId: Long,
//        val depth: Int,
//        val path: String,
//    ): Serializable
//
//    private fun <T: Any> JdbcTransaction.execCTE(stmt: String, transform: (ResultSet) -> T): List<T> {
//        if (stmt.isBlank()) return emptyList()
//        if (!stmt.trim().startsWith("WITH RECURSIVE", ignoreCase = true)) {
//            throw IllegalArgumentException("Not a CTE statement: $stmt")
//        }
//
//        val type = StatementType.SELECT
//
//        val cte = object: Statement<List<T>>(type, emptyList()) {
//            override fun arguments(): Iterable<Iterable<Pair<IColumnType<*>, Any?>>> = emptyList()
//
//            override fun prepareSQL(transaction: JdbcTransaction, prepared: Boolean): String {
//                return stmt
//            }
//
//            override fun PreparedStatementApi.executeInternal(transaction: JdbcTransaction): List<T> {
//                executeQuery()
//                return resultSet?.use {
//                    val results = mutableListOf<T>()
//                    while (it.next()) {
//                        results.add(transform(it))
//                    }
//                    results
//                } ?: emptyList()
//            }
//        }
//
//        return exec(cte) ?: emptyList()
//    }
//}
