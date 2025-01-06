package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withDb
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.jetbrains.exposed.sql.SchemaUtils
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.SQLException

class CreateDatabaseTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop database`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in TestDB.ALL_H2 }

        withDb(testDb) {
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop database with auto commit`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in TestDB.ALL_H2 }

        withDb(testDb) {
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

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `list databases with auto commit`(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in TestDB.ALL_H2 }

        withDb(testDb) {
            connection.autoCommit = true

            val dbName = "jetbrains"
            val initial = SchemaUtils.listDatabases()
            if (dbName in initial) {
                SchemaUtils.dropDatabase(dbName)
            }

            SchemaUtils.createDatabase(dbName)
            SchemaUtils.listDatabases() shouldContain dbName

            SchemaUtils.dropDatabase(dbName)
            SchemaUtils.listDatabases() shouldNotContain dbName

            connection.autoCommit = false
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `list databases `(testDb: TestDB) {
        Assumptions.assumeTrue { testDb in TestDB.ALL_H2 }

        withDb(testDb) {
            val dbName = "jetbrains"
            val initial = SchemaUtils.listDatabases()
            if (dbName in initial) {
                SchemaUtils.dropDatabase(dbName)
            }

            SchemaUtils.createDatabase(dbName)
            SchemaUtils.listDatabases() shouldContain dbName

            SchemaUtils.dropDatabase(dbName)
            SchemaUtils.listDatabases() shouldNotContain dbName
        }
    }
}
