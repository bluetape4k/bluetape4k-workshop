package io.bluetape4k.workshop.exposed.controller

import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test

class ControllerCoroutineScopeTest {

    @Test
    fun `webflux controllers do not keep their own coroutine scope`() {
        (ActorController(mockk()) is CoroutineScope).shouldBeFalse()
        (MovieController(mockk()) is CoroutineScope).shouldBeFalse()
        (MovieActorsController(mockk()) is CoroutineScope).shouldBeFalse()
    }
}
