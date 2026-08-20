package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

internal class FieldServiceBrowserContractTest {
    @Test
    fun `console keeps CSP safe DOM only polling and hidden tab contracts`() {
        val html = resource("static/field-service/index.html")
        check("Content-Security-Policy" in html)
        check("script-src 'self'" in html)
        check("aria-live=\"polite\"" in html)
        check("/field-service/app.js" in html)
        check("id=\"plans\"" in html)
        check("계획" in html)

        val script = resource("static/field-service/app.js")
        check("innerHTML" !in script)
        check("insertAdjacentHTML" !in script)
        check("eval(" !in script)
        check("textContent" in script)
        check("Promise.all" in script)
        check("/api/field-service/plans" in script)
        check("requestEpoch !== visibilityEpoch" in script)
        check("scoreSummary" in script)
        check("constraint" in script)
        check("manualPin" in script)
        check("visibilitychange" in script)
        check("clearTimeout" in script)
        check("2000" in script)
        check("POST" !in script)
    }

    private fun resource(path: String): String {
        val resource = ClassPathResource(path)
        check(resource.exists()) { "missing static resource: $path" }
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
