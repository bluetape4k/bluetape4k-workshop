package io.bluetape4k.workshop.micrometer.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.micrometer.AbstractTracingTest
import io.bluetape4k.workshop.micrometer.model.Todo
import io.bluetape4k.workshop.shared.web.httpGet
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.returnResult

class CoroutineControllerTest: AbstractTracingTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get name in coroutines`() = runSuspendIO {
        webTestClient
            .httpGet("/coroutine/name")
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .apply {
                log.debug { "body=$this" }
            }
            .shouldNotBeNull()
            .shouldNotBeEmpty()
    }

    @Test
    fun `get todo in coroutines`() = runSuspendIO {
        val id = 42

        val todo = webTestClient
            .httpGet("/coroutine/todos/$id")
            .expectStatus().is2xxSuccessful
            .returnResult<Todo>().responseBody
            .awaitSingle()

        log.debug { "todo: $todo" }
        todo.id shouldBeEqualTo id
    }
}
