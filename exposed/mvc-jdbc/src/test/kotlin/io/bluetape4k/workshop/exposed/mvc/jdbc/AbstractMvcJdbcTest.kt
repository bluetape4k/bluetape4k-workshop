package io.bluetape4k.workshop.exposed.mvc.jdbc

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@ActiveProfiles("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractMvcJdbcTest {

    companion object : KLogging() {
        private val testDB = TestDB.POSTGRESQL

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { testDB.connection() }
            registry.add("spring.datasource.driver-class-name") { testDB.driver }
            registry.add("spring.datasource.username") { testDB.user }
            registry.add("spring.datasource.password") { testDB.pass }
        }

        val faker = Fakers.faker
    }

    @LocalServerPort
    protected val port: Int = 0

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }
}
