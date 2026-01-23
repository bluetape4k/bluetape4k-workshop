package io.bluetape4k.workshop.exposed.spring.transaction

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.codec.Base58
import io.bluetape4k.workshop.exposed.spring.transaction.SpringTransactionEntityTest.OrderService
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.TransactionManagementConfigurer
import javax.sql.DataSource

@Configuration
@EnableTransactionManagement
class TestConfig: TransactionManagementConfigurer {

    @Bean
    fun dataSource(): DataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:${Base58.randomString(8)};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL"
            driverClassName = "org.h2.Driver"
            username = "root"
            password = ""
        }
        return HikariDataSource(config)
    }

    @Bean
    override fun annotationDrivenTransactionManager(): PlatformTransactionManager =
        SpringTransactionManager(dataSource(), DatabaseConfig { useNestedTransactions = true })

    @Bean
    fun orderService(): OrderService = OrderService()

}
