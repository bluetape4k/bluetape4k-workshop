package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withDb
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.SQLException

class CreateDatabaseTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop database`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withDb(testDB) {
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

    /**
     * Postgres 는 DROP DATABASE 같은 작업 시 `autoCommit` 이 `true` 여야 합니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop database with auto commit`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 + TestDB.ALL_POSTGRES }

        withDb(testDB) {
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
    fun `list databases `(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withDb(testDB) {
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

    /**
     * Postgres 는 DROP DATABASE 같은 작업 시 `autoCommit` 이 `true` 여야 합니다.
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `list databases with auto commit`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 + TestDB.ALL_POSTGRES }

        withDb(testDB) {
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
}
