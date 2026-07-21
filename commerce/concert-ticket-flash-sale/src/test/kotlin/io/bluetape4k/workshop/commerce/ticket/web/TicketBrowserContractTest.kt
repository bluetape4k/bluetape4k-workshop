package io.bluetape4k.workshop.commerce.ticket.web

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

internal class TicketBrowserContractTest {
    @Test
    fun `demo exposes accessible recovery status and bounded fallback`() {
        val html = staticText("index.html")
        check("aria-live=\"polite\"" in html)
        check("data-polling-fallback" in html)
        check("data-status=" in html)
        check("aria-label=" in html)

        val script = staticText("app.js")
        check("innerHTML" !in script)
        check("textContent" in script)
        check("localStorage" !in script)
        check("document.cookie" !in script)
        check("disconnectSseForTest" in script)
        check("cache: 'no-store'" in script)

        val styles = staticText("styles.css")
        check("prefers-reduced-motion" in styles)
        check(":focus-visible" in styles)
    }

    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        check(resource.exists()) { "static resource is missing: $name" }
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
