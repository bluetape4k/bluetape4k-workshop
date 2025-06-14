package io.bluetape4k.workshop.exposed

import io.bluetape4k.jdbc.JdbcDrivers
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import kotlin.reflect.full.declaredMemberProperties

const val USE_TESTCONTAINERS = true
const val USE_FAST_DB = false

enum class TestDB(
    val connection: () -> String,
    val driver: String,
    val user: String = "test",
    val pass: String = "test",
    val beforeConnection: () -> Unit = {},
    val afterConnection: (connection: Connection) -> Unit = {},
    val afterTestFinished: () -> Unit = {},
    val dbConfig: DatabaseConfig.Builder.() -> Unit = {},
) {
    /**
     * H2 v1.+ 를 사용할 때
     */
    H2_V1(
        connection = { "jdbc:h2:mem:regular-v1;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;" },
        driver = JdbcDrivers.DRIVER_CLASS_H2,
        dbConfig = {
            defaultIsolationLevel = java.sql.Connection.TRANSACTION_READ_COMMITTED
        }
    ),

    /**
     * H2 v2.+ 를 사용할 때
     */
    H2(
        connection = { "jdbc:h2:mem:regular-v2;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;" },
        driver = JdbcDrivers.DRIVER_CLASS_H2,
        dbConfig = {
            defaultIsolationLevel = java.sql.Connection.TRANSACTION_READ_COMMITTED
        }
    ),
    H2_MYSQL(
        connection = {
            "jdbc:h2:mem:mysql;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
        },
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
    H2_MARIADB(
        connection = {
            "jdbc:h2:mem:mariadb;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
        },
        driver = JdbcDrivers.DRIVER_CLASS_H2,
    ),
    H2_PSQL(
        connection = {
            "jdbc:h2:mem:psql;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
        },
        driver = JdbcDrivers.DRIVER_CLASS_H2
    ),


    MARIADB(
        connection = {
            val options = "?useSSL=false" +
                    "&characterEncoding=UTF-8" +
                    "&useLegacyDatetimeCode=false&serverTimezone=UTC" +  // TimeZone 을 UTC 로 설정
                    "&zeroDateTimeBehavior=convertToNull"  // +
            // "&rewriteBatchedStatements=true"

            if (USE_TESTCONTAINERS) {
                ContainerProvider.mariadb.jdbcUrl + options
            } else {
                "jdbc:mariadb://localhost:3306/exposed$options"
            }
        },
        driver = JdbcDrivers.DRIVER_CLASS_MARIADB
    ),

    MYSQL_V5(
        connection = {
            val options = "?useSSL=false" +
                    "&characterEncoding=UTF-8" +
                    "&useLegacyDatetimeCode=false" +
                    "&serverTimezone=UTC" +  // TimeZone 을 UTC 로 설정
                    "&zeroDateTimeBehavior=convertToNull"  // +
            // "&rewriteBatchedStatements=true"
            if (USE_TESTCONTAINERS) {
                ContainerProvider.mysql5.jdbcUrl + options
            } else {
                "jdbc:mysql://localhost:3306/exposed$options"
            }
        },
        driver = JdbcDrivers.DRIVER_CLASS_MYSQL
    ),

    MYSQL_V8(
        connection = {
            val options = "?useSSL=false" +
                    "&characterEncoding=UTF-8" +
                    "&zeroDateTimeBehavior=convertToNull" +
                    "&useLegacyDatetimeCode=false" +
                    "&serverTimezone=UTC" +  // TimeZone 을 UTC 로 설정
                    "&allowPublicKeyRetrieval=true" // +
            //  "&rewriteBatchedStatements=true"                        // Batch 처리를 위한 설정

            if (USE_TESTCONTAINERS) {
                ContainerProvider.mysql8.jdbcUrl + options
            } else {
                "jdbc:mysql://localhost:3306/exposed$options"
            }
        },
        driver = JdbcDrivers.DRIVER_CLASS_MYSQL,
        user = if (USE_TESTCONTAINERS) "test" else "exposed",
        pass = if (USE_TESTCONTAINERS) "test" else "@exposed2025"
    ),

    POSTGRESQL(
        connection = {
            val options = "?lc_messages=en_US.UTF-8"
            if (USE_TESTCONTAINERS) {
                ContainerProvider.postgres.jdbcUrl + options
            } else {
                "jdbc:postgresql://localhost:5432/exposed$options"
            }
        },
        driver = JdbcDrivers.DRIVER_CLASS_POSTGRESQL,
        user = if (USE_TESTCONTAINERS) "test" else "exposed",
        afterConnection = { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("SET TIMEZONE='UTC';")
            }
        }
    ),

    POSTGRESQLNG(
        connection = { POSTGRESQL.connection().replace(":postgresql:", ":pgsql:") },
        driver = "com.impossibl.postgres.jdbc.PGDriver",
        user = if (USE_TESTCONTAINERS) "test" else "exposed",
        afterConnection = { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("SET TIMEZONE='UTC';")
            }
        }
    ),

    COCKROACH(
        connection = {
            ContainerProvider.cockroach.getJdbcUrl() + "?allowMultiQueries=true" // + "?sslmode=disable"
        },
        driver = JdbcDrivers.DRIVER_CLASS_POSTGRESQL,
        afterConnection = { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("SET autocommit_before_ddl = on")
            }
        },
        dbConfig = {
            defaultIsolationLevel = java.sql.Connection.TRANSACTION_READ_COMMITTED
        }
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
            password = pass,
            driver = driver,
            setupConnection = { afterConnection(it) },
        )
    }

    companion object: KLogging() {
        val ALL_H2_V1 = setOf(H2_V1)
        val ALL_H2 = setOf(H2, H2_MYSQL, H2_PSQL, H2_MARIADB)
        val ALL_MARIADB = setOf(MARIADB)
        val ALL_MARIADB_LIKE = setOf(MARIADB, H2_MARIADB)
        val ALL_MYSQL = setOf(MYSQL_V5, MYSQL_V8)
        val ALL_MYSQL_MARIADB = ALL_MYSQL + ALL_MARIADB
        val ALL_MYSQL_LIKE = ALL_MYSQL_MARIADB + H2_MYSQL + H2_MARIADB
        val ALL_POSTGRES = setOf(POSTGRESQL, POSTGRESQLNG)
        val ALL_POSTGRES_LIKE = setOf(POSTGRESQL, POSTGRESQLNG, H2_PSQL)
        val ALL_COCKROACH = setOf(COCKROACH)

        val ALL = TestDB.entries.toSet()

        // NOTE: 이 값을 바꿔서 MySQL, PostgreSQL 등을 testcontainers 를 이용하여 테스트할 수 있습니다.

        fun enabledDialects(): Set<TestDB> {
            return if (USE_FAST_DB) ALL_H2 else (ALL_H2 + ALL_POSTGRES + ALL_MYSQL_MARIADB) //ALL - ALL_H2_V1 - MYSQL_V5 - COCKROACH)
        }
    }
}
