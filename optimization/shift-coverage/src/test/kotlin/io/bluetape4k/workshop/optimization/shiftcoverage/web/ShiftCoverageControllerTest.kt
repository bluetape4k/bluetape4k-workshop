package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageDemoService
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

class ShiftCoverageControllerTest {
    @Test
    fun `demo manager can replan and worker read is redacted to own assignment`() {
        val controller = ShiftCoverageController(ShiftCoverageDemoService())
        val manager = MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
            addHeader("X-Demo-Operator", "manager-demo")
            addHeader("X-Demo-Role", "manager")
        }
        val response = controller.replan(manager, "key-1", "request-1")
        response.statusCode shouldBeEqualTo HttpStatus.ACCEPTED
        response.body?.accepted.shouldBeTrue()

        val replay = controller.replan(manager, "key-1", "request-2")
        replay.body?.revision shouldBeEqualTo response.body?.revision
        replay.body?.requestId shouldBeEqualTo "request-1"

        val next = controller.replan(manager, "key-2", "request-3")
        (next.body?.revision ?: 0L) shouldBeEqualTo (response.body?.revision ?: 0L) + 1L

        val hostileOrigin = MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
            addHeader("Origin", "http://localhost.evil")
            addHeader("X-Demo-Operator", "manager-demo")
            addHeader("X-Demo-Role", "manager")
        }
        assertFailsWith<ShiftCoverageHttpException> { controller.plans(hostileOrigin) }
            .code shouldBeEqualTo "ORIGIN_FORBIDDEN"
    }
}
