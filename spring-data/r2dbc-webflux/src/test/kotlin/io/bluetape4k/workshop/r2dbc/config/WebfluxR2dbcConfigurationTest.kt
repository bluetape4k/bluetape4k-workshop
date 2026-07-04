package io.bluetape4k.workshop.r2dbc.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.r2dbc.AbstractWebfluxR2dbcApplicationTest
import io.bluetape4k.workshop.r2dbc.handler.UserHandler
import io.bluetape4k.workshop.r2dbc.service.UserService
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class WebfluxR2dbcConfigurationTest @Autowired constructor(
    private val userHandler: UserHandler,
    private val userService: UserService,
) : AbstractWebfluxR2dbcApplicationTest() {

    companion object : KLoggingChannel()

    @Test
    fun `context loading`() {
        userHandler.shouldNotBeNull()
        userService.shouldNotBeNull()
    }
}
