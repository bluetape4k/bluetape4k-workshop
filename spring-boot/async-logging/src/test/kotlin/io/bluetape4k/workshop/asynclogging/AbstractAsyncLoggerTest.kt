package io.bluetape4k.workshop.asynclogging

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractAsyncLoggerTest {

    companion object: KLoggingChannel()
}
