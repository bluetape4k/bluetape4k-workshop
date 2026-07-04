package io.bluetape4k.workshop.messaging.fallback

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.mq.KafkaServer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractKafkaOutboxFallbackTest {

    companion object : KLogging() {
        val postgres = PostgreSQLServer.Launcher.postgres
        val kafka = KafkaServer.Launcher.kafka
        val faker = Fakers.faker

        @JvmStatic
        @DynamicPropertySource
        fun containerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username.requireNotNull("postgres.username") }
            registry.add("spring.datasource.password") { postgres.password.requireNotNull("postgres.password") }
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
            registry.add("workshop.kafka-outbox-fallback.direct-publish-timeout") { "50ms" }
            registry.add("workshop.kafka-outbox-fallback.direct-publish-total-timeout") { "200ms" }
            registry.add("workshop.kafka-outbox-fallback.reconciler-grace") { "1ms" }
            registry.add("workshop.kafka-outbox-fallback.relay-enabled") { false }
            registry.add("workshop.kafka-outbox-fallback.reconciler-enabled") { false }
            registry.add("workshop.kafka-outbox-fallback.demo-admin-endpoints-enabled") { false }
        }
    }

    @LocalServerPort
    protected val port: Int = 0

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }
}
