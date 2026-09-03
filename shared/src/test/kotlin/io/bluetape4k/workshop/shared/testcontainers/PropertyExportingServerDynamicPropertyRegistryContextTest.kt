package io.bluetape4k.workshop.shared.testcontainers

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.PropertyExportingServer
import io.bluetape4k.testcontainers.spring.registerDynamicProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig

/**
 * fake server의 동적 프로퍼티가 Spring [Environment]와
 * [ConfigurationProperties] binding까지 도달하는지 Docker 없이 확인합니다.
 */
@SpringJUnitConfig(classes = [PropertyExportingServerDynamicPropertyRegistryContextTest.TestConfig::class])
class PropertyExportingServerDynamicPropertyRegistryContextTest @Autowired constructor(
    private val environment: Environment,
    private val endpoint: BridgeRedisProperties,
) {

    @Test
    fun `bridge 값이 Environment 와 configuration properties 에 binding 된다`() {
        environment.getProperty("testcontainers.redis.host") shouldBeEqualTo "localhost"
        environment.getProperty("testcontainers.redis.port") shouldBeEqualTo "6380"
        environment.getProperty("testcontainers.redis.url") shouldBeEqualTo "redis://localhost:6380"

        endpoint.host shouldBeEqualTo "localhost"
        endpoint.port shouldBeEqualTo 6380
        endpoint.url shouldBeEqualTo "redis://localhost:6380"
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BridgeRedisProperties::class)
    class TestConfig {

        @Bean
        fun fakeServer(): PropertyExportingServer = contextServer
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            contextServer.registerDynamicProperties(registry)
        }
    }
}

@ConfigurationProperties(prefix = "testcontainers.redis")
class BridgeRedisProperties {
    var host: String = ""
    var port: Int = 0
    var url: String = ""
}

private val contextServer = object : PropertyExportingServer {
    override val propertyNamespace: String = "redis"

    override fun propertyKeys(): Set<String> = setOf("host", "port", "url")

    override fun properties(): Map<String, String> = mapOf(
        "host" to "localhost",
        "port" to "6380",
        "url" to "redis://localhost:6380",
    )
}
