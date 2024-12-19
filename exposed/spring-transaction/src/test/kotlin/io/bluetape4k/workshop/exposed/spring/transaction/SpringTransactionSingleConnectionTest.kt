package io.bluetape4k.workshop.exposed.spring.transaction

import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.spring.SpringTransactionManager
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import javax.sql.DataSource
import kotlin.test.AfterTest

class SpringTransactionSingleConnectionTest {

    object T1: Table() {
        val c1 = varchar("c1", Int.MIN_VALUE.toString().length)
    }

    val singleConnectionH2TestContainer = AnnotationConfigApplicationContext(SingleConnectionH2TestConfig::class.java)
    val dataSource = singleConnectionH2TestContainer.getBean(DataSource::class.java)
    val transactionManager = singleConnectionH2TestContainer.getBean(PlatformTransactionManager::class.java)

    @BeforeEach
    fun beforeEach() {
        transactionManager.execute {
            SchemaUtils.create(T1)
        }
    }

    @AfterTest
    fun afterEach() {
        transactionManager.execute {
            SchemaUtils.drop(T1)
        }
    }

    @Test
    fun `start transaction with non default isolation level`() {
        transactionManager.execute(
            isolationLevel = TransactionDefinition.ISOLATION_SERIALIZABLE,
        ) {
            T1.selectAll().toList()
        }
    }

    @Test
    fun `nested transaction with non default isolation level`() {
        transactionManager.execute(
            isolationLevel = TransactionDefinition.ISOLATION_SERIALIZABLE,
        ) {
            T1.selectAll().toList()

            // Nested transaction will inherit isolation level from parent transaction because is use the same connection
            transactionManager.execute(
                isolationLevel = TransactionDefinition.ISOLATION_READ_UNCOMMITTED,
            ) {
                DataSourceUtils.getConnection(dataSource).use { connection ->
                    connection.transactionIsolation shouldBeEqualTo TransactionDefinition.ISOLATION_SERIALIZABLE
                }
                T1.selectAll().toList()
            }
            T1.selectAll().toList()
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    class SingleConnectionH2TestConfig {

        @Bean
        fun singleConnectionH2DataSource(): DataSource {
            return SingleConnectionDataSource(
                "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;",
                "sa",
                "",
                true
            )
        }

        @Bean
        fun transactionManager(
            @Qualifier("singleConnectionH2DataSource") dataSource: DataSource,
        ): PlatformTransactionManager {
            return SpringTransactionManager(dataSource = dataSource, DatabaseConfig { useNestedTransactions = true })
        }
    }
}
