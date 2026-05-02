package io.bluetape4k.workshop.messaging.kafka.controller

import kotlinx.coroutines.CoroutineScope
import org.amshove.kluent.shouldBeFalse
import org.junit.jupiter.api.Test

class GreetingControllerScopeTest {

    @Test
    fun `greeting controller does not keep its own coroutine scope`() {
        (GreetingController() is CoroutineScope).shouldBeFalse()
    }
}
