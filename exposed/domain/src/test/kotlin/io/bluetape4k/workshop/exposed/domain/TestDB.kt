package io.bluetape4k.workshop.exposed.domain

import io.bluetape4k.jdbc.JdbcDrivers
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import kotlin.reflect.full.declaredMemberProperties

enum class TestDB(
    val connection: () -> String,
    val driver: String,
    val user: String = "test",
    val password: String = "test",
    val beforeConnection: () -> Unit = {},
    val afterTestFinished: () -> Unit = {},
    val dbConfig: DatabaseConfig.Builder.() -> Unit = {},
) {

    H2(
        connection = { "jdbc:h2:mem:regular;DB_CLOSE_DELAY=-1;" },
        driver = JdbcDrivers.DRIVER_CLASS_H2,
        dbConfig = {
            defaultIsolationLevel = java.sql.Connection.TRANSACTION_READ_COMMITTED
        }
    ),
    H2_MYSQL(
        connection = { "jdbc:h2:mem:mysql;MODE=MySQL;DB_CLOSE_DELAY=-1" },
        driver = JdbcDrivers.DRIVER_CLASS_H2,
        beforeConnection = {
            org.h2.engine.Mode::class.declaredMemberProperties
                .firstOrNull { it.name == "convertInsertNullToZero" }
                ?.let { field ->
                    val mode = org.h2.engine.Mode.getInstance("MySQL")
                    @Suppress("UNCHECKED_CAST")
                    (field as kotlin.reflect.KMutableProperty1<org.h2.engine.Mode, Boolean>).set(mode, false)
                }
        }
    ),
    H2_PSQL(
        connection = { "jdbc:h2:mem:psql;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1" },
        driver = JdbcDrivers.DRIVER_CLASS_H2
    ),

    MYSQL_V5(
        connection = {
            ContainerProvider.mysql5.jdbcUrl +
                    "?useSSL=false" +
                    "&characterEncoding=UTF-8" +
                    "&zeroDateTimeBehavior=convertToNull"
        },
        driver = JdbcDrivers.DRIVER_CLASS_MYSQL
    ),

    MYSQL_V8(
        connection = {
            ContainerProvider.mysql8.jdbcUrl +
                    "?useSSL=false" +
                    "&characterEncoding=UTF-8" +
                    "&zeroDateTimeBehavior=convertToNull" +
                    "&allowPublicKeyRetrieval=true"
        },
        driver = JdbcDrivers.DRIVER_CLASS_MYSQL
    ),

    POSTGRESQL(
        connection = {
            ContainerProvider.postgres.jdbcUrl +
                    "/testdb" +
                    "&lc_messages=en_US.UTF-8"
        },
        driver = JdbcDrivers.DRIVER_CLASS_POSTGRESQL
    );

    var db: Database? = null

    fun connect(configure: DatabaseConfig.Builder.() -> Unit = {}): Database {
        val config = DatabaseConfig {
            dbConfig()
            configure()
        }
        return Database.connect(
            url = connection(),
            databaseConfig = config,
            user = user,
            password = password,
            driver = driver
        )
    }

    companion object: KLogging() {
        val ALL_H2 = setOf(H2, H2_MYSQL, H2_PSQL)
        val ALL_MYSQL = setOf(MYSQL_V5, MYSQL_V8)
        val ALL_MYSQL_LIKE = ALL_MYSQL + H2_MYSQL
        val ALL_POSTGRES = setOf(POSTGRESQL)
        val ALL_POSTGRES_LIKE = setOf(POSTGRESQL, H2_PSQL)

        val All = TestDB.entries.toSet()

        fun enabledDialects(): Set<TestDB> {
            return entries.toSet() - MYSQL_V5
        }
    }
}
