package io.bluetape4k.workshop.gateway

import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class GatewayRedisConfigurationContractTest {

    @Test
    fun `redis testcontainer overrides have local development fallbacks`() {
        val config = Files.readString(projectRoot().resolve("gateway/api-gateway/src/main/resources/application.yml"))

        config.shouldContain("\${testcontainers.redis.host:localhost}")
        config.shouldContain("\${testcontainers.redis.port:6379}")
        config.shouldContain("\${testcontainers.redis.url:redis://localhost:6379}")
    }

    private fun projectRoot(): Path = listOf(Path.of("."), Path.of("../.."))
        .first { Files.exists(it.resolve("gateway/api-gateway/build.gradle.kts")) }
}
