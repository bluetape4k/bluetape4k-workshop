package io.bluetape4k.workshop.r2dbc

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ClassPathResource
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration
import org.springframework.r2dbc.connection.init.CompositeDatabasePopulator
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator

@SpringBootApplication
class R2dbcApplication: AbstractR2dbcConfiguration() {

    companion object: KLoggingChannel()

    override fun connectionFactory(): ConnectionFactory {
        // TODO: 환경설정 때문에 h2 로 테스트 했습니다.
        // TODO: 실제 데이터 입출력은 Postgres 를 이용해야 합니다.
        val url = "r2dbc:h2:mem:///test?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        return ConnectionFactories.get(url)
    }

    @Bean
    fun initializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer {
        log.info { "Initialize Database ..." }
        return ConnectionFactoryInitializer().apply {
            setConnectionFactory(connectionFactory)
            val populator = CompositeDatabasePopulator().apply {
                addPopulators(ResourceDatabasePopulator(ClassPathResource("data/schema.sql")))
            }
            setDatabasePopulator(populator)
            afterPropertiesSet()
        }
    }
}

fun main(vararg args: String) {
    runApplication<R2dbcApplication>(*args)
}
