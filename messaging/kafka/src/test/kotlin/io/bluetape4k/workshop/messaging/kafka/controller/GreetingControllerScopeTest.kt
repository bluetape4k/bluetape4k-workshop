package io.bluetape4k.workshop.messaging.kafka.controller

import io.bluetape4k.assertions.shouldBeFalse
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.Test

class GreetingControllerScopeTest {

    @Test
    fun `greeting controller does not keep its own coroutine scope`() {
        (GreetingController() is CoroutineScope).shouldBeFalse()
    }
}
