package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.dao.EntityHook
import org.jetbrains.exposed.sql.Key
import org.jetbrains.exposed.sql.Schema
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import java.util.*

abstract class AbstractExposedTest {

    companion object: KLogging() {
        @JvmStatic
        val faker = Fakers.faker

        init {
            EntityHook.subscribe { change ->
                log.info {
                    """
                    ${change.entityClass.table.tableName} id[${change.entityId}] was ${change.changeType}
                """.trimIndent()
                }
            }
        }

        @JvmStatic
        fun enableDialects() = TestDB.enabledDialects()

        const val ENABLE_DIALECTS_METHOD = "enableDialects"
    }

    init {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private object CurrentTestDBInterceptor: StatementInterceptor {
        override fun keepUserDataInTransactionStoreOnCommit(userData: Map<Key<*>, Any?>): Map<Key<*>, Any?> {
            return userData.filterValues { it is TestDB }
        }
    }

//    protected lateinit var database: Database
//
//    @BeforeAll
//    fun setup() {
//        database = connectDatabase(hikariDataSource())
//    }
//
//    private fun hikariConfigH2(): HikariConfig {
//        return HikariConfig().also {
//            it.driverClassName = JdbcDrivers.DRIVER_CLASS_H2
//            it.jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;"
//            it.username = "sa"
//            it.password = ""
//        }
//    }
//
//    private fun hikariDataSource(): HikariDataSource {
//        return HikariDataSource(hikariConfigH2())
//    }
//
//    private fun connectDatabase(dataSource: DataSource): Database {
//        return Database.connect(dataSource)
//    }

    fun addIfNotExistsIfSupported() = if (currentDialectTest.supportsIfNotExists) {
        "IF NOT EXISTS "
    } else {
        ""
    }

    protected fun prepareSchemaForTest(schemaName: String): Schema = Schema(
        schemaName,
        defaultTablespace = "USERS",
        temporaryTablespace = "TEMP ",
        quota = "20M",
        on = "USERS"
    )
}
