package io.bluetape4k.workshop.commerce.voucher.web

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

internal class VoucherBrowserContractTest {
    @Test
    fun `static console contains keyboard live status and secret handling contracts`() {
        val html = staticText("index.html")
        check("aria-live=\"polite\"" in html)
        check("aria-label=" in html)
        check("type=\"password\"" in html)

        val script = staticText("app.js")
        check("innerHTML" !in script)
        check("textContent" in script)
        check("restoreFocus" in script)
        check("localStorage" !in script)
        check("document.cookie" !in script)
        check("clearOperatorSecret" in script)
        check("history." !in script)
        check("alternateSnapshotPath" in script)
        check("alternate.origin !== location.origin" in script)
        check("fallbackAttempts > 10" in script)
        check("operator-disabled-reason" in script)
    }

    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        check(resource.exists()) { "static resource is missing: $name" }
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
