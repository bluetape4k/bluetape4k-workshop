package io.bluetape4k.workshop.optimization.shiftcoverage.web

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

internal class ShiftCoverageBrowserContractTest {
    @Test
    fun `console uses CSP safe DOM rendering and bounded command states`() {
        val html = resource("static/shift-coverage/index.html")
        check("Content-Security-Policy" in html)
        check("script-src 'self'" in html)
        check("connect-src 'self'" in html)
        check("aria-live=\"polite\"" in html)
        check("defer" in html)

        val script = resource("static/shift-coverage/shift-coverage.js")
        check("innerHTML" !in script)
        check("insertAdjacentHTML" !in script)
        check("eval(" !in script)
        check("textContent" in script)
        check("Retry-After" in script)
        check("REPLAN_REJECTED" in script)
        check("RESPONSE_TOO_LARGE" in script)
        check("REVISION_CONFLICT" in script)
        check("response.status === 202" in script)
        check("response.status === 429" in script)
        check("response.status === 413" in script)
    }

    private fun resource(path: String): String {
        val resource = ClassPathResource(path)
        check(resource.exists()) { "missing static resource: $path" }
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
