package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withDb
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.jetbrains.exposed.sql.SchemaUtils
import org.junit.jupiter.api.Test
import java.sql.SQLException

class CreateDatabaseTest: AbstractExposedTest() {

    companion object: KLogging()

    @Test
    fun `create and drop database`() {
        withDb(excludeSettings = listOf(TestDB.POSTGRESQL, TestDB.COCKROACH)) {
            val dbName = "jetbrains"
            try {
                SchemaUtils.dropDatabase(dbName)
            } catch (cause: SQLException) {
                // ignore
            }
            SchemaUtils.createDatabase(dbName)
            SchemaUtils.dropDatabase(dbName)
        }
    }

    @Test
    fun `create and drop database with auto commit`() {
        withDb(excludeSettings = listOf(TestDB.POSTGRESQL, TestDB.COCKROACH)) {
            connection.autoCommit = true
            val dbName = "jetbrains"
            try {
                SchemaUtils.dropDatabase(dbName)
            } catch (cause: SQLException) {
                // ignore
            }
            SchemaUtils.createDatabase(dbName)
            SchemaUtils.dropDatabase(dbName)
            connection.autoCommit = false
        }
    }

    @Test
    fun `list databases with auto commit`() {
        withDb(excludeSettings = listOf(TestDB.POSTGRESQL, TestDB.COCKROACH)) {
            connection.autoCommit = true

            val dbName = "jetbrains"
            val initial = SchemaUtils.listDatabases()
            if (dbName in initial) {
                SchemaUtils.dropDatabase(dbName)
            }

            SchemaUtils.createDatabase(dbName)
            val created = SchemaUtils.listDatabases()
            created shouldContain dbName

            SchemaUtils.dropDatabase(dbName)
            val deleted = SchemaUtils.listDatabases()
            deleted shouldNotContain dbName

            connection.autoCommit = false
        }
    }

    @Test
    fun `list databases `() {
        withDb(excludeSettings = listOf(TestDB.POSTGRESQL, TestDB.COCKROACH)) {
            val dbName = "jetbrains"
            val initial = SchemaUtils.listDatabases()
            if (dbName in initial) {
                SchemaUtils.dropDatabase(dbName)
            }

            SchemaUtils.createDatabase(dbName)
            val created = SchemaUtils.listDatabases()
            created shouldContain dbName

            SchemaUtils.dropDatabase(dbName)
            val deleted = SchemaUtils.listDatabases()
            deleted shouldNotContain dbName
        }
    }
}
