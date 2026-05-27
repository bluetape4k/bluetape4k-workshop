package io.bluetape4k.workshop.micrometer.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.micrometer.AbstractTracingTest
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class CoroutineServiceTest(
    @param:Autowired private val service: CoroutineService,
): AbstractTracingTest() {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        service.shouldNotBeNull()
    }

    @Test
    fun `get name`() = runSuspendIO {
        service.getName().shouldNotBeEmpty()
    }

    @Test
    fun `get todo by id`() = runSuspendIO {
        val id = 42

        val todo = service.getTodo(id)

        log.debug { "todo: $todo" }
        todo.shouldNotBeNull()
        todo.id shouldBeEqualTo id
    }
}
