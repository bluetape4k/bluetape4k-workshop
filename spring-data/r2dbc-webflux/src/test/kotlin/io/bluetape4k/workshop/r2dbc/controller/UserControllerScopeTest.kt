package io.bluetape4k.workshop.r2dbc.controller

import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import org.amshove.kluent.shouldBeFalse
import org.junit.jupiter.api.Test

class UserControllerScopeTest {

    @Test
    fun `user controller does not keep its own coroutine scope`() {
        (UserController(mockk()) is CoroutineScope).shouldBeFalse()
    }
}
