package io.bluetape4k.workshop.exposed.webflux.r2dbc.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.utils.Runtimex
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Duration
import kotlin.math.max

@Configuration(proxyBeanMethods = false)
class ExposedR2dbcConfig {

    companion object : KLoggingChannel()

    @Bean
    fun databaseCoroutineDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Bean
    fun connectionFactoryOptions(
        @Value("\${spring.r2dbc.url}") r2dbcUrl: String,
        @Value("\${spring.r2dbc.username}") username: String,
        @Value("\${spring.r2dbc.password}") password: String,
    ): ConnectionFactoryOptions {
        val options = ConnectionFactoryOptions.parse(r2dbcUrl)
            .mutate()
            .option(ConnectionFactoryOptions.USER, username)
            .option(ConnectionFactoryOptions.PASSWORD, password)
            .build()
        log.info { "ConnectionFactoryOptions created: driver=${options.getValue(ConnectionFactoryOptions.DRIVER)}" }
        return options
    }

    @Bean
    @Primary
    fun connectionPool(connectionFactoryOptions: ConnectionFactoryOptions): ConnectionPool {
        val connectionFactory = ConnectionFactories.get(connectionFactoryOptions)
        val poolConfig = ConnectionPoolConfiguration.builder(connectionFactory)
            .maxIdleTime(Duration.ofMinutes(30))
            .initialSize(5)
            .minIdle(5)
            .maxSize(max(Runtimex.availableProcessors * 8, 100))
            .acquireRetry(3)
            .maxAcquireTime(Duration.ofSeconds(3))
            .validationQuery("SELECT 1")
            .build()
        return ConnectionPool(poolConfig)
    }

    @Bean
    fun r2dbcDatabase(
        connectionPool: ConnectionPool,
        connectionFactoryOptions: ConnectionFactoryOptions,
        databaseCoroutineDispatcher: CoroutineDispatcher,
    ): R2dbcDatabase {
        val config = R2dbcDatabaseConfig {
            this.dispatcher = databaseCoroutineDispatcher
            this.connectionFactoryOptions = connectionFactoryOptions
        }
        log.info { "R2dbcDatabase configured (pool-backed)" }
        return R2dbcDatabase.connect(connectionPool, config)
    }
}
