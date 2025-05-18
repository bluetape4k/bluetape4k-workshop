package io.bluetape4k.workshop.gatling

import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractGatlingTest {

    companion object: KLogging()

}
