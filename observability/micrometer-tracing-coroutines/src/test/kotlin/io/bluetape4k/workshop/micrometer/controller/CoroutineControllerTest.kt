package io.bluetape4k.workshop.micrometer.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.micrometer.AbstractTracingTest
import io.bluetape4k.workshop.micrometer.model.Todo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult

class CoroutineControllerTest: AbstractTracingTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get name in coroutines`() = runSuspendIO {
        webTestClient
            .get()
            .uri("/coroutine/name")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<String>().responseBody
            .asFlow()
            .toList()
            .joinToString("")
            .shouldNotBeEmpty()
            .apply {
                log.debug { "body=$this" }
            }
    }

    @Test
    fun `get todo in coroutines`() = runSuspendIO {
        val id = 42

        val todo = webTestClient
            .get()
            .uri("/coroutine/todos/$id")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<Todo>().responseBody
            .awaitSingle()

        log.debug { "todo: $todo" }
        todo.shouldNotBeNull()
        todo.id shouldBeEqualTo id
    }
}
