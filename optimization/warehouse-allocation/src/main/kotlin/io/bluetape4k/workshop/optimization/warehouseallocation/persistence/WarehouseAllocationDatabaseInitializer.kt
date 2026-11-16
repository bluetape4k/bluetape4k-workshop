package io.bluetape4k.workshop.optimization.warehouseallocation.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
@Profile("demo")
internal class WarehouseAllocationDatabaseInitializer {

    companion object: KLogging()

    @Bean
    fun warehouseAllocationSchemaRunner(): ApplicationRunner = ApplicationRunner {
        log.info { "Create Warehouse Allocation Tables ..." }
        transaction {
            SchemaUtils.create(*WarehouseAllocationTables.all)
        }
    }
}
