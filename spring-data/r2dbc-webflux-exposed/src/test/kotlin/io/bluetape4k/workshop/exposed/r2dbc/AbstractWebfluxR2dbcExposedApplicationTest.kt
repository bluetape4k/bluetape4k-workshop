package io.bluetape4k.workshop.exposed.r2dbc

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.exposed.r2dbc.domain.model.UserRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractWebfluxR2dbcExposedApplicationTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        val faker = Fakers.faker
    }

    @Autowired
    protected val context: ApplicationContext = uninitialized()

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
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
