package io.bluetape4k.workshop.exposed.spring.boot.autoconfigure

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.exposed.spring.boot.Application
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring.boot.DatabaseInitializer
import org.jetbrains.exposed.v1.spring.transaction.SpringTransactionManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import kotlin.test.assertFailsWith

@SpringBootTest(
    classes = [Application::class, ExposedAutoConfigurationTest.CustomDatabaseConfigConfiguration::class],
    properties = [
        "spring.datasource.url=jdbcLh2:mem:test",
        "spring.datasource.driver-class-name=org.h2.Driver"
    ]
)
class ExposedAutoConfigurationTest {

    companion object: KLogging()

    /**
     * Exposed 의 SpringTransactionManager 빈이 생성되었는지 확인
     */
    @Autowired(required = false)
    private val springTransactionManager: SpringTransactionManager = uninitialized()

    @Autowired(required = false)
    private val databaseInitializer: DatabaseInitializer = uninitialized()

    @Autowired
    private val databaseConfig: DatabaseConfig = uninitialized()

    @Test
    fun `should initialize the database connection`() {
        springTransactionManager.shouldNotBeNull()
    }

    @Test
    fun `database config can be override by custom one`() {
        val expectedConfig = CustomDatabaseConfigConfiguration.expectedConfig
        databaseConfig shouldBeEqualTo expectedConfig

        databaseConfig.maxEntitiesToStoreInCachePerEntity shouldBeEqualTo
                expectedConfig.maxEntitiesToStoreInCachePerEntity
    }

    @Test
    fun `class excluded from auto config`() {
        // Application 만 사용하면 DataSourceTransactionManagerAutoConfiguration 빈이 없음
        val contextRunner = ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(Application::class.java)
        )

        contextRunner.run { context ->
            assertFailsWith<NoSuchBeanDefinitionException> {
                context.getBean(DataSourceTransactionManagerAutoConfiguration::class.java)
            }
        }
    }

    @TestConfiguration
    class CustomDatabaseConfigConfiguration {

        companion object {
            val expectedConfig = DatabaseConfig {
                maxEntitiesToStoreInCachePerEntity = 777
            }
        }

        @Bean
        fun customDatabaseConfig(): DatabaseConfig = expectedConfig
    }
}
