package io.bluetape4k.workshop.commerce.order

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

internal class InfrastructureConfigurationTest {
    private val properties =
        YamlPropertySourceLoader()
            .load("application", ClassPathResource("application.yml"))
            .single()

    @Test
    fun `Tomcat records eight thousand connection and fallback thread limits`() {
        properties.getProperty("server.tomcat.threads.max") shouldBeEqualTo
            "${'$'}{ORDER_TOMCAT_MAX_THREADS:8000}"
        properties.getProperty("server.tomcat.max-connections") shouldBeEqualTo
            "${'$'}{ORDER_TOMCAT_MAX_CONNECTIONS:8000}"
        properties.getProperty("server.tomcat.connection-timeout") shouldBeEqualTo
            "${'$'}{ORDER_TOMCAT_CONNECTION_TIMEOUT:60s}"
        properties.getProperty("server.tomcat.keep-alive-timeout") shouldBeEqualTo
            "${'$'}{ORDER_TOMCAT_KEEP_ALIVE_TIMEOUT:60s}"
    }

    @Test
    fun `database concurrency remains bounded while wait time increases`() {
        properties.getProperty("spring.datasource.hikari.maximum-pool-size") shouldBeEqualTo
            "${'$'}{ORDER_DB_POOL_MAX:8}"
        properties.getProperty("spring.datasource.hikari.minimum-idle") shouldBeEqualTo
            "${'$'}{ORDER_DB_POOL_MIN_IDLE:2}"
        properties.getProperty("spring.datasource.hikari.connection-timeout") shouldBeEqualTo
            "${'$'}{ORDER_DB_CONNECTION_TIMEOUT_MS:60000}"
        properties.getProperty("spring.transaction.default-timeout") shouldBeEqualTo
            "${'$'}{ORDER_TRANSACTION_TIMEOUT:60s}"
    }
}
