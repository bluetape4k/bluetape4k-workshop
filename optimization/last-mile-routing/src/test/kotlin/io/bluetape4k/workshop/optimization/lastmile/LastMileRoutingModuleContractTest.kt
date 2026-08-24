package io.bluetape4k.workshop.optimization.lastmile

import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test

class LastMileRoutingModuleContractTest {

    @Test
    fun `application entry point belongs to the lastmile package`() {
        val application = Class.forName("io.bluetape4k.workshop.optimization.lastmile.LastMileRoutingApplication")

        application.shouldNotBeNull()
    }
}
