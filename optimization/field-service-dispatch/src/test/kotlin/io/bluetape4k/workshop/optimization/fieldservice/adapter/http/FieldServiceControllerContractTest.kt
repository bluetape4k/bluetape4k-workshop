package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceApprovalService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceCommandService
import io.bluetape4k.workshop.optimization.fieldservice.application.FieldServiceDispatchService
import io.bluetape4k.workshop.optimization.fieldservice.config.FieldServiceConfiguration
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEventType
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import io.bluetape4k.workshop.optimization.fieldservice.planner.DeterministicFieldServicePlanner
import jakarta.servlet.http.HttpServletRequestWrapper
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Profile
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Instant

internal class FieldServiceControllerContractTest {
    private val repository = FieldServiceRepository()
    private val controller = FieldServiceController(
        repository = repository,
        service = FieldServiceHttpService(
            repository = repository,
            commandService = FieldServiceCommandService(repository),
            planner = DeterministicFieldServicePlanner(),
            approvalService = FieldServiceApprovalService(repository),
            dispatchService = FieldServiceDispatchService(repository),
        ),
    )

    @Test
    fun `mutation without demo operator and idempotency key fails before repository access`() {
        val request = CreateVisitRequest(
            visitId = "visit-1",
            coordinateId = "coordinate-1",
            requiredSkill = "electrical",
            windowStart = Instant.parse("2026-08-20T09:00:00Z"),
            windowEnd = Instant.parse("2026-08-20T10:00:00Z"),
            serviceDurationSeconds = 60,
        )
        assertFailsWith<InvalidFieldServiceInput> {
            controller.createVisit(request, operator = null, key = null)
        }
    }

    @Test
    fun `input and page bounds remain centralized`() {
        FieldServiceLimits.MAX_BODY_BYTES shouldBeEqualTo 256 * 1024
        FieldServiceLimits.MAX_PAGE_SIZE shouldBeEqualTo 100
        FieldServiceEventType.entries.shouldNotBeEmpty()
    }

    @Test
    fun `dispatcher routes and beans are demo profile only`() {
        FieldServiceController::class.java.getAnnotation(Profile::class.java).value.toList() shouldContain "demo"
        FieldServiceConsoleController::class.java.getAnnotation(Profile::class.java).value.toList() shouldContain "demo"
        FieldServiceConfiguration::class.java.getAnnotation(Profile::class.java).value.toList() shouldContain "demo"
    }

    @Test
    fun `chunked mutation body over the limit is returned as 413`() {
        val source = MockHttpServletRequest("POST", "/api/field-service/visits").apply {
            setContent(ByteArray(FieldServiceLimits.MAX_BODY_BYTES + 1) { 'x'.code.toByte() })
        }
        val request = object : HttpServletRequestWrapper(source) {
            override fun getContentLength(): Int = -1

            override fun getContentLengthLong(): Long = -1L
        }
        val response = MockHttpServletResponse()

        FieldServiceBodyLimitFilter().doFilter(request, response) { wrapped, _ ->
            wrapped.inputStream.readBytes()
        }

        response.status shouldBeEqualTo 413
        response.contentAsString shouldContain "BODY_TOO_LARGE"
    }
}
