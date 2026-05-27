package io.bluetape4k.workshop.exposed.r2dbc

import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.schema.SchemaInitializer
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@ActiveProfiles("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractWebfluxR2dbcExposedApplicationTest {

    companion object: KLoggingChannel() {
        private val testDB = TestDB.POSTGRESQL

        @JvmStatic
        val faker = Fakers.faker

        @JvmStatic
        @DynamicPropertySource
        fun r2dbcProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") { testDB.connection() }
            registry.add("spring.r2dbc.username") { testDB.user }
            registry.add("spring.r2dbc.password") { testDB.pass }
        }
    }

    @Autowired
    protected val context: ApplicationContext = uninitialized()

    @Autowired
    private val schemaInitializer: SchemaInitializer = uninitialized()

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

    @BeforeEach
    fun ensureSchema() {
        schemaInitializer.initializeSchema()
    }

    protected fun createUser(id: Int? = null): UserRecord =
        UserRecord(
            name = faker.name().fullName(),
            login = faker.credentials().username(),
            email = faker.internet().emailAddress(),
            avatar = faker.avatar().image(),
            id = id ?: -1
        )

    protected fun createUserRecord(): UserRecord =
        UserRecord(
            name = faker.name().fullName(),
            login = faker.credentials().username(),
            email = faker.internet().emailAddress(),
            avatar = faker.avatar().image()
        )
}
