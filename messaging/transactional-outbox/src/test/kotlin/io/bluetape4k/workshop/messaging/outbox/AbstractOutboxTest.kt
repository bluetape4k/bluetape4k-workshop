package io.bluetape4k.workshop.messaging.outbox

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.mq.KafkaServer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractOutboxTest {

    companion object : KLogging() {
        val postgres = PostgreSQLServer.Launcher.postgres
        val kafka = KafkaServer.Launcher.kafka

        @JvmStatic
        @DynamicPropertySource
        fun containerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl.shouldNotBeNull() }
            registry.add("spring.datasource.username") { postgres.username.shouldNotBeNull() }
            registry.add("spring.datasource.password") { postgres.password.shouldNotBeNull() }
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }

        val faker = Fakers.faker
    }

    @LocalServerPort
    protected val port: Int = 0

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }
}
