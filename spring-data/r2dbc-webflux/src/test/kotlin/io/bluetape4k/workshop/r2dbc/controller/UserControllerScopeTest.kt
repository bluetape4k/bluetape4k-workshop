package io.bluetape4k.workshop.r2dbc.controller

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.r2dbc.service.UserService
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.Test

class UserControllerScopeTest {

    companion object: KLogging()

    @Test
    fun `user controller does not keep its own coroutine scope`() {
        (UserController(mockk<UserService>()) is CoroutineScope).shouldBeFalse()
    }
}
