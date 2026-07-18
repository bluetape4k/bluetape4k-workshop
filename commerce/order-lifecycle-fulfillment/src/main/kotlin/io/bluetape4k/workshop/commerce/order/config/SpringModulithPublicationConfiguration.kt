package io.bluetape4k.workshop.commerce.order.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import io.bluetape4k.spring.modulith.exposed.config.ExposedModulithProperties
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
internal class SpringModulithPublicationConfiguration {
    @Bean("springTransactionManager")
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean
    fun eventPublicationRepository(
        eventPublicationTable: ExposedEventPublicationTable,
        eventPublicationArchiveTable: ExposedEventPublicationTable,
        serializer: EventSerializer,
        properties: ExposedModulithProperties,
    ): ExposedEventPublicationRepository =
        ExposedEventPublicationRepository(
            table = eventPublicationTable,
            archiveTable = eventPublicationArchiveTable,
            serializer = serializer,
            completionMode = properties.completionMode
        ).also {
            log.info { "exposed_event_publication_repository_configured completionMode=${properties.completionMode}" }
        }

    companion object : KLogging()
}
