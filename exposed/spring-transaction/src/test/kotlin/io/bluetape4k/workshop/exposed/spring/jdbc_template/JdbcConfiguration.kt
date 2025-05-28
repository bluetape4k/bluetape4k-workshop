package io.bluetape4k.workshop.exposed.spring.jdbc_template

import org.jetbrains.exposed.v1.spring.transaction.SpringTransactionManager
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class JdbcConfiguration {

    @Bean
    @Qualifier("operations1")
    fun operations1(transactionManager: SpringTransactionManager): TransactionOperations {
        return TransactionTemplate(transactionManager)
    }

    @Bean
    @Qualifier("operations2")
    fun operation2(): TransactionOperations =
        TransactionOperations.withoutTransaction()
}
