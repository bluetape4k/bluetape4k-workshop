package io.bluetape4k.workshop.commerce.reservation

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.reservationTables
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.client.reactive.JdkClientHttpConnector
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal abstract class AbstractReservationIntegrationTest {

    companion object: KLogging() {
        val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
        }
    }

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    private lateinit var resources: CapacityResourceRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun resetReservationFixture() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            SchemaUtils.drop(*reservationTables.reversedArray())
            SchemaUtils.create(*reservationTables)
            resources.create("demo-room-utc", capacity = 1, policyVersion = 1)
        }
    }

    protected val webTestClient: WebTestClient by lazy {
        val connector = JdkClientHttpConnector().apply {
            setReadTimeout(60.seconds())
        }

        WebTestClient
            .bindToServer(connector)
            .baseUrl("http://localhost:$port")
            .responseTimeout(60.seconds())
            .build()
    }
}
