package io.bluetape4k.workshop.exposed.domain

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.jdbc.JdbcDrivers
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.dao.EntityHook
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.BeforeAll
import javax.sql.DataSource

abstract class AbstractExposedDomainTest {

    companion object: KLogging() {
        @JvmStatic
        val faker = Fakers.faker

        init {
            EntityHook.subscribe { change ->
                log.info {
                    """
                    ${change.entityClass.javaClass.simpleName} with id
                    ${change.entityId} was ${change.changeType}
                """.trimIndent()
                }
            }
        }
    }

    protected lateinit var database: Database

    @BeforeAll
    fun setup() {
        database = connectDatabase(hikariDataSource())
    }

    private fun hikariConfigH2(): HikariConfig {
        return HikariConfig().also {
            it.driverClassName = JdbcDrivers.DRIVER_CLASS_H2
            it.jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;"
            it.username = "sa"
            it.password = ""
        }
    }

    private fun hikariDataSource(): HikariDataSource {
        return HikariDataSource(hikariConfigH2())
    }

    private fun connectDatabase(dataSource: DataSource): Database {
        return Database.connect(dataSource)
    }
}
