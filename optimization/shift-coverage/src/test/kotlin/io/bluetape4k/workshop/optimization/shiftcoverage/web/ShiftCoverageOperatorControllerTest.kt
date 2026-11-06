package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageProvider
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageInboxService
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageOutboxRedriveRejected
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageOutboxStore
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

class ShiftCoverageOperatorControllerTest {

    companion object: KLogging()

    @Test
    fun `non-manager cannot requeue and does not touch inbox`() {
        val inbox = ShiftCoverageInboxService()
        val controller = ShiftCoverageOperatorController(inbox, ShiftCoverageOutboxStore())
        val request = MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
        }

        assertFailsWith<ShiftCoverageHttpException> {
            controller.requeue(
                request,
                "FAKE",
                "event-1",
                "worker-a-demo",
                "worker",
                "operator reason"
            )
        }.status shouldBeEqualTo HttpStatus.FORBIDDEN

        inbox.find(ShiftCoverageProvider.FAKE, EventId("event-1")).shouldBeNull()
    }

    @Test
    fun `redrive rejection is mapped to a stable conflict response`() {
        val request: HttpServletRequest = MockHttpServletRequest().apply {
            addHeader("X-Request-Id", "request-1")
        }

        val response = ShiftCoverageExceptionHandler()
            .handleOutboxRedrive(
                exception = ShiftCoverageOutboxRedriveRejected("internal provider state"),
                request = request,
            )

        log.debug { "response=$response" }
        response.statusCode shouldBeEqualTo HttpStatus.CONFLICT
        response.body?.code shouldBeEqualTo "OUTBOX_REDRIVE_REJECTED"
        response.body?.requestId shouldBeEqualTo "request-1"
    }
}
