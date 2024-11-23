package io.bluetape4k.workshop.virtualthread.undertow

import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractUndertowVirtualThreadMvcTest {

    companion object: KLogging()

}
