package io.bluetape4k.workshop.chaos.config

import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/** Spring `DataSource`를 Exposed JDBC `Database`로 연결하는 예제 설정입니다. */
@Configuration(proxyBeanMethods = false)
class ExposedDatabaseConfig {

    @Bean
    fun database(dataSource: DataSource): Database = Database.connect(dataSource)
}
