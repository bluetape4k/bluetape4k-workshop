package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

internal class FieldServiceBrowserContractTest {
    @Test
    fun `console keeps CSP safe DOM only polling and hidden tab contracts`() {
        val html = resource("static/field-service/index.html")
        html shouldContain "Content-Security-Policy"
        html shouldContain "script-src 'self'"
        html shouldContain "aria-live=\"polite\""
        html shouldContain "/field-service/app.js"
        html shouldContain "id=\"plans\""
        html shouldContain "계획"

        val script = resource("static/field-service/app.js")
        script shouldNotContain "innerHTML"
        script shouldNotContain "insertAdjacentHTML"
        script shouldNotContain "eval("
        script shouldContain "textContent"
        script shouldContain "Promise.all"
        script shouldContain "/api/field-service/plans"
        script shouldContain "requestEpoch !== visibilityEpoch"
        script shouldContain "scoreSummary"
        script shouldContain "constraint"
        script shouldContain "manualPin"
        script shouldContain "visibilitychange"
        script shouldContain "clearTimeout"
        script shouldContain "2000"
        script shouldNotContain "POST"
    }

    private fun resource(path: String): String {
        val resource = ClassPathResource(path)
        resource.exists().shouldBeTrue()
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
