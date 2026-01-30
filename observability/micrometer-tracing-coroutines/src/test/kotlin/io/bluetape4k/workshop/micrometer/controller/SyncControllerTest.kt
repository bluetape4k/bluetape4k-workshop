package io.bluetape4k.workshop.micrometer.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
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

class SyncControllerTest: AbstractTracingTest() {

    companion object: KLogging()

    @Test
    fun `get name in sync`() = runSuspendIO {
        webTestClient
            .httpGet("/sync/name")
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
    fun `get todo in sync`() = runSuspendIO {
        val id = 42
        val todo = webTestClient
            .httpGet("/sync/todos/$id")
            .expectStatus().is2xxSuccessful
            .returnResult<Todo>().responseBody
            .awaitSingle()

        log.debug { "todo: $todo" }
        todo.id shouldBeEqualTo id
    }
}
