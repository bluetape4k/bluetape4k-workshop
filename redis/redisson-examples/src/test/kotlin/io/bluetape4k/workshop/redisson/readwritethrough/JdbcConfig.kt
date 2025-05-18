package io.bluetape4k.workshop.redisson.readwritethrough

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class JdbcConfig {

    companion object: KLogging()

    @Bean
    fun dataSource(): DataSource {
        return EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .addScripts(
                "classpath:database/schema.sql",
                "classpath:database/data.sql"
            )
            .build()
    }

    @Bean
    fun jdbcTemplate(ds: DataSource): JdbcTemplate {
        return JdbcTemplate(ds)
    }

    @Bean
    @ConditionalOnMissingBean
    fun transactionManager(ds: DataSource): PlatformTransactionManager {
        return DataSourceTransactionManager(ds)
    }
}
