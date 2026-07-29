package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import org.junit.jupiter.api.Test
import java.util.UUID

class JobConsoleProblemHandlerTest {
    private val handler = JobConsoleProblemHandler()

    @Test
    fun `problem correlations use UUID version seven`() {
        val validation = handler.validation().body.shouldNotBeNull()
        val conflict = handler.repository(JobRepositoryException(JobProblemCode.IDEMPOTENCY_KEY_REUSED)).body.shouldNotBeNull()

        UUID.fromString(validation.requestId).version() shouldBeEqualTo 7
        UUID.fromString(conflict.requestId).version() shouldBeEqualTo 7
    }
}
