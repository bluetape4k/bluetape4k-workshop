package io.bluetape4k.workshop.mapstruct

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging

abstract class AbstractMapstructTest {

    companion object: KLogging() {
        @JvmStatic
        val faker = Fakers.faker
    }
}
