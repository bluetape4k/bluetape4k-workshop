package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

internal class TicketBrowserContractTest {
    @Test
    fun `demo exposes accessible recovery status and bounded fallback`() {
        val html = staticText("index.html")
        html shouldContain "aria-live=\"polite\""
        html shouldContain "data-polling-fallback"
        html shouldContain "data-status="
        html shouldContain "aria-label="

        val script = staticText("app.js")
        script shouldNotContain "innerHTML"
        script shouldContain "textContent"
        script shouldNotContain "localStorage"
        script shouldNotContain "document.cookie"
        script shouldContain "disconnectSseForTest"
        script shouldContain "cache: 'no-store'"

        val styles = staticText("styles.css")
        styles shouldContain "prefers-reduced-motion"
        styles shouldContain ":focus-visible"
    }

    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        resource.exists().shouldBeTrue()
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
