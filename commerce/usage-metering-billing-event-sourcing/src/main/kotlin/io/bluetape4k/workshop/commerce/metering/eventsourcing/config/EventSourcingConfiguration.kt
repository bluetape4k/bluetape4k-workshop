package io.bluetape4k.workshop.commerce.metering.eventsourcing.config

import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandReceiptService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.CommandReceiptRepository
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource
import org.springframework.transaction.PlatformTransactionManager

@Configuration(proxyBeanMethods = false)
class EventSourcingConfiguration {
    @Bean
    fun eventSourcingClock(): Clock = Clock.systemUTC()

    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean
    fun commandReceiptService(repository: CommandReceiptRepository): CommandReceiptService =
        CommandReceiptService(repository, COMMAND_LEASE_DURATION, COMMAND_RETENTION)

    private companion object {
        val COMMAND_LEASE_DURATION: Duration = Duration.ofSeconds(30)
        val COMMAND_RETENTION: Duration = Duration.ofDays(7)
    }
}
