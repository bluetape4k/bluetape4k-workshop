package io.bluetape4k.workshop.commerce.reservation

import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import reactor.netty.resources.LoopResources
import java.time.Duration
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal abstract class AbstractReservationIntegrationTest {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var resources: CapacityResourceRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val clientConnectionProvider =
        lazy {
            ConnectionProvider.create("reservation-webtest-$port", 32)
        }

    private val clientLoopResources =
        lazy {
            LoopResources.create("reservation-webtest-$port", 2, true)
        }

    @BeforeEach
    fun resetReservationFixture() {
        JdbcTemplate(dataSource).execute(
            """
            TRUNCATE TABLE
              reservation_notification_deliveries,
              reservation_http_idempotency,
              reservation_transition_audits,
              reservation_offers,
              reservation_waitlist_entries,
              reservation_holds,
              reservation_capacity_resources
            RESTART IDENTITY CASCADE
            """.trimIndent()
        )
        TransactionTemplate(transactionManager).executeWithoutResult {
            resources.create("demo-room-utc", capacity = 1, policyVersion = 1)
        }
    }

    /** Keeps live HTTP tests independent from application-context Reactor Netty shutdown. */
    protected val webTestClient: WebTestClient by lazy {
        val httpClient =
            HttpClient
                .create(clientConnectionProvider.value)
                .runOn(clientLoopResources.value)
        WebTestClient
            .bindToServer(ReactorClientHttpConnector(httpClient))
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(60))
            .build()
    }

    @AfterAll
    fun closeWebTestClientResources() {
        if (clientConnectionProvider.isInitialized()) {
            clientConnectionProvider.value.disposeLater().block(Duration.ofSeconds(5))
        }
        if (clientLoopResources.isInitialized()) {
            clientLoopResources.value.disposeLater().block(Duration.ofSeconds(5))
        }
    }

    companion object {
        val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
        }
    }
}
