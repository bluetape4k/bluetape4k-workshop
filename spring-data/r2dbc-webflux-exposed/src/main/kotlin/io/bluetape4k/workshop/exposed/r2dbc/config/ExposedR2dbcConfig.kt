package io.bluetape4k.workshop.exposed.r2dbc.config

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

    /**
     * Java 21 가상 스레드 기반 코루틴 컨텍스트를 제공합니다.
     * 데이터베이스 I/O 작업에 최적화되어 있습니다.
     */
    @Bean(destroyMethod = "")
    fun databaseCoroutineDispatcher(): CoroutineDispatcher {
        return Dispatchers.IO // 기본적으로 IO 디스패처를 사용합니다.
        // return Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    }

    @Bean
    fun connectionFactoryOptions(
        @Value("\${spring.r2dbc.url:r2dbc:h2:mem:///test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}") r2dbcUrl: String,
        @Value("\${spring.r2dbc.username:}") username: String,
        @Value("\${spring.r2dbc.password:}") password: String,
    ): ConnectionFactoryOptions {
        val builder = ConnectionFactoryOptions.parse(r2dbcUrl).mutate()
        if (username.isNotBlank()) {
            builder.option(ConnectionFactoryOptions.USER, username)
        }
        if (password.isNotBlank()) {
            builder.option(ConnectionFactoryOptions.PASSWORD, password)
        }
        val options = builder
            .build()

        log.info { "R2DBC 연결 설정: driver=${options.getValue(ConnectionFactoryOptions.DRIVER)}" }
        return options
    }

    /**
     * ConnectionFactory를 래핑하여 커넥션 풀을 구성합니다.
     * 모든 데이터베이스 유형에 공통으로 적용됩니다.
     */
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

    /**
     * ConnectionPool을 사용하여 Exposed R2DBC 데이터베이스 객체를 생성합니다.
     */
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

        log.info { "R2DBC Database 설정 완료 (connectionPool 기반)" }
        return R2dbcDatabase.connect(connectionPool, config)
    }
}
