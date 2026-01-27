package io.bluetape4k.workshop.micrometer.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.micrometer.AbstractTracingTest
import io.bluetape4k.workshop.micrometer.model.Todo
import io.bluetape4k.workshop.shared.web.httpGet
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

class SyncControllerTest: AbstractTracingTest() {

    companion object: KLogging()

    @Test
    fun `get name in sync`() {
        webTestClient
            .httpGet("/sync/name")
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult().responseBody
            .shouldNotBeNull()
            .shouldNotBeEmpty()
    }

    @Test
    fun `get todo in sync`() {
        val id = 42
        val todo = webTestClient
            .httpGet("/sync/todos/$id")
            .expectStatus().is2xxSuccessful
            .expectBody<Todo>()
            .returnResult().responseBody

        log.debug { "todo: $todo" }
        todo.shouldNotBeNull()
        todo.id shouldBeEqualTo id
    }
}
