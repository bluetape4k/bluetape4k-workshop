package io.bluetape4k.workshop.r2dbc

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.r2dbc.domain.Comment
import io.bluetape4k.workshop.r2dbc.domain.Post
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@ActiveProfiles("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractR2dbcApplicationTest {

    @Autowired
    private var applicationContext: ApplicationContext? = null

    protected val context: ApplicationContext
        get() = checkNotNull(applicationContext) { "applicationContext is not injected." }

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

    companion object : KLoggingChannel() {
        private val postgres: PostgreSQLServer by lazy { PostgreSQLServer.Launcher.postgres }

        @JvmStatic
        val faker = Fakers.faker

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLServer.PORT)}/${postgres.databaseName}"
            }
            registry.add("spring.r2dbc.username") { checkNotNull(postgres.username) }
            registry.add("spring.r2dbc.password") { checkNotNull(postgres.password) }
            registry.add("spring.sql.init.mode") { "always" }
        }
    }

    protected fun createPost(): Post =
        Post(
            title = faker.book().title(),
            content = Fakers.fixedString(255)
        )

    protected fun createComment(postId: Long): Comment =
        Comment(
            postId = postId,
            content = Fakers.fixedString(255)
        )
}
