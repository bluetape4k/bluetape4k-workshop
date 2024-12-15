package io.bluetape4k.workshop.exposed.spring.transaction

import org.jetbrains.exposed.spring.SpringTransactionManager
import org.jetbrains.exposed.sql.DatabaseConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.TransactionManagementConfigurer

@Configuration
@EnableTransactionManagement
class TestConfig: TransactionManagementConfigurer {

    @Bean
    fun database(): EmbeddedDatabase =
        EmbeddedDatabaseBuilder()
            .setName("embeddedTest")
            .setType(EmbeddedDatabaseType.H2)
            .build()

    @Bean
    override fun annotationDrivenTransactionManager(): PlatformTransactionManager =
        SpringTransactionManager(database(), DatabaseConfig { useNestedTransactions = true })

//    @Bean
//    open fun service(): Service = Service()

}
