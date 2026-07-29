package io.bluetape4k.workshop.commerce.usagebilling.usage.config

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class UsageServiceConfiguration {
    @Bean
    fun usageClock(): Clock = Clock.systemUTC()

    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)
}
