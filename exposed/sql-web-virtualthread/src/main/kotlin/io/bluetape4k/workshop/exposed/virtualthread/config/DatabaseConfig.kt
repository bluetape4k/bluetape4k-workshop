package io.bluetape4k.workshop.exposed.virtualthread.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.MySQL8Server
import io.bluetape4k.testcontainers.database.getDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import javax.sql.DataSource

/**
 * exposed-spring-boot-starter 를 사용하면 되지만, 여기서는 직접 설정했다
 */
@Configuration
class DatabaseConfig {

    companion object: KLogging() {
        val mysql by lazy { MySQL8Server.Launcher.mysql }
    }

    @Bean
    @Profile("h2")
    fun dataSourceH2(): DataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;"
            driverClassName = "org.h2.Driver"
            username = "sa"
            password = ""
        }
        return HikariDataSource(config)
    }

    @Bean
    @Profile("mysql")
    fun dataSourceMySQL(): DataSource {
        return mysql.getDataSource()
    }

    @Bean
    fun database(dataSource: DataSource): Database {
        return Database.connect(dataSource)
    }
}
