package io.bluetape4k.workshop.spring.security.webflux.jwt

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
abstract class AbstractJwtApplicationTest {

    companion object: KLoggingChannel()
}
