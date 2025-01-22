package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.workshop.exposed.TestDB
import org.jetbrains.exposed.dao.id.IntIdTable

object RollbackTable: IntIdTable("rollbackTable") {
    val value = varchar("value", 20)
}

// Explanation: MariaDB driver never set readonly to true, MSSQL silently ignores the call, SQLite does not
// promise anything, H2 has very limited functionality
val READ_ONLY_EXCLUDED_VENDORS = TestDB.ALL_H2
// + TestDB.ALL_MARIADB + listOf(TestDB.SQLITE, TestDB.SQLSERVER, TestDB.ORACLE)
