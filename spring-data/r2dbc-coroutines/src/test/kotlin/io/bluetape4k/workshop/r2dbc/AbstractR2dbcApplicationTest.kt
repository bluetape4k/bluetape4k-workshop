package io.bluetape4k.workshop.r2dbc

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.r2dbc.domain.Comment
import io.bluetape4k.workshop.r2dbc.domain.Post
import org.junit.jupiter.api.Disabled
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

@Disabled("Spring Boot가 자동 스키마 생성을 못한다. 수동 생성으로 변경해야 한다")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractR2dbcApplicationTest {

    @Autowired
    protected val context: ApplicationContext = uninitialized()

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

    companion object: KLoggingChannel() {
        @JvmStatic
        val faker = Fakers.faker
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
