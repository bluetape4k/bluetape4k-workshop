package io.bluetape4k.workshop.exposed.r2dbc

import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import io.bluetape4k.workshop.exposed.r2dbc.domain.schema.SchemaInitializer
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
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

    companion object : KLoggingChannel() {
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
    private var applicationContext: ApplicationContext? = null

    protected val context: ApplicationContext
        get() = checkNotNull(applicationContext) { "applicationContext is not injected." }

    @Autowired
    private var injectedSchemaInitializer: SchemaInitializer? = null

    @Autowired
    private var injectedDatabase: R2dbcDatabase? = null

    private val schemaInitializer: SchemaInitializer
        get() = checkNotNull(injectedSchemaInitializer) { "schemaInitializer is not injected." }

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

    @BeforeEach
    fun ensureSchema() {
        // QBE/FluentQuery terminal은 Exposed 기본 DB를 사용하므로, 다이얼렉트 계약
        // 테스트가 전역 값을 바꾼 뒤에도 애플리케이션 DB를 복원한다.
        TransactionManager.defaultDatabase = checkNotNull(injectedDatabase) {
            "r2dbcDatabase is not injected."
        }
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
