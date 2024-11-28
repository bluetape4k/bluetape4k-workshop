package io.bluetape4k.workshop.exposed.virtualthread.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.exposed.virtualthread.AbstractExposedTest
import org.junit.jupiter.api.Test

class ConfigurationTest: AbstractExposedTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        log.info { "Context loading test" }
    }
}
