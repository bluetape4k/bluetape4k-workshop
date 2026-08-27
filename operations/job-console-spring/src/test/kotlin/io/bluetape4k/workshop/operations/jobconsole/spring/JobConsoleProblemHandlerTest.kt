package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.operations.jobconsole.api.JobProblem
import io.bluetape4k.workshop.operations.jobconsole.api.JobConsoleJson
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import org.junit.jupiter.api.Test
import java.util.UUID

class JobConsoleProblemHandlerTest {
    private val handler = JobConsoleProblemHandler()
    private val mapper = JobConsoleJson.defaultMapper

    @Test
    fun `problem correlations use UUID version seven`() {
        val validation = mapper.readValue(handler.validation().body.shouldNotBeNull(), JobProblem::class.java)
        val conflict = mapper.readValue(
            handler.repository(JobRepositoryException(JobProblemCode.IDEMPOTENCY_KEY_REUSED)).body.shouldNotBeNull(),
            JobProblem::class.java,
        )

        UUID.fromString(validation.requestId).version() shouldBeEqualTo 7
        UUID.fromString(conflict.requestId).version() shouldBeEqualTo 7
    }
}
