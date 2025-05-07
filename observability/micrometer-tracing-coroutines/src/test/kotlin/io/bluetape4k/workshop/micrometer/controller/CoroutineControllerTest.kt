package io.bluetape4k.workshop.micrometer.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.micrometer.AbstractTracingTest
import io.bluetape4k.workshop.micrometer.model.Todo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class CoroutineControllerTest(
    @Autowired private val client: WebTestClient,
): AbstractTracingTest() {

    companion object: KLogging()

    @Test
    fun `get name in coroutines`() = runSuspendIO {
        client
            .httpGet("/coroutine/name")
            .returnResult<String>().responseBody
            .asFlow()
            .toList()
            .shouldNotBeEmpty()
    }

    @Test
    fun `get todo in coroutines`() = runSuspendIO {
        val id = 42

        val todo = client
            .httpGet("/coroutine/todos/$id")
            .returnResult<Todo>().responseBody
            .awaitSingle()

        log.debug { "todo: $todo" }
        todo.shouldNotBeNull()
        todo.id shouldBeEqualTo id
    }
}
