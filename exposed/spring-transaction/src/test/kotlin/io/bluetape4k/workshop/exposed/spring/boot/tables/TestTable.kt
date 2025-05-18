package io.bluetape4k.workshop.exposed.spring.boot.tables

import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * ```sql
 * -- H2
 * CREATE TABLE IF NOT EXISTS TEST_TABLE (
 *      ID INT AUTO_INCREMENT PRIMARY KEY,
 *      "name" VARCHAR(100) NOT NULL
 * )
 * ```
 */
object TestTable: IntIdTable("test_table") {
    val name = varchar("name", 100)
}
