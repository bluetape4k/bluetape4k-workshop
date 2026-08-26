package io.bluetape4k.workshop.optimization.shiftcoverage.web

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

internal class ShiftCoverageBrowserContractTest {
    @Test
    fun `console uses CSP safe DOM rendering and bounded command states`() {
        val html = resource("static/shift-coverage/index.html")
        html shouldContain "Content-Security-Policy"
        html shouldContain "script-src 'self'"
        html shouldContain "connect-src 'self'"
        html shouldContain "aria-live=\"polite\""
        html shouldContain "defer"

        val script = resource("static/shift-coverage/shift-coverage.js")
        script shouldNotContain "innerHTML"
        script shouldNotContain "insertAdjacentHTML"
        script shouldNotContain "eval("
        script shouldContain "textContent"
        script shouldContain "Retry-After"
        script shouldContain "REPLAN_REJECTED"
        script shouldContain "RESPONSE_TOO_LARGE"
        script shouldContain "REVISION_CONFLICT"
        script shouldContain "response.status === 202"
        script shouldContain "response.status === 429"
        script shouldContain "response.status === 413"
    }

    private fun resource(path: String): String {
        val resource = ClassPathResource(path)
        resource.exists().shouldBeTrue()
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
