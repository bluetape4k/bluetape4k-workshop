package io.bluetape4k.workshop.exposed.mvc.vt.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

    companion object : KLogging()

    @Bean
    fun database(
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
        @Value("\${spring.datasource.driver-class-name:org.postgresql.Driver}") driverClassName: String,
    ): Database {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            this.username = username
            this.password = password
            this.driverClassName = driverClassName
            maximumPoolSize = 20
            minimumIdle = 5
        })
        return Database.connect(ds)
    }
}
