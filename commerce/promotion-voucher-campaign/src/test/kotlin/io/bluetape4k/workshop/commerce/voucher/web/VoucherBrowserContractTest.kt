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
        check("id=\"run-scenario\"" in html)
        check("id=\"reset-scenario\"" in html)
        check("id=\"review-list\"" in html)
        check("id=\"reconciliation-list\"" in html)
        check("id=\"refresh-operator-evidence\"" in html)
        check("delayed-duplicate-out-of-order" in html)

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
        check("X-Workshop-Origin" in script)
        check("executeScenario" in script)
        check("Promise.allSettled" in script)
        check("Expected exactly one terminal winner" in script)
        check("Expected remaining capacity 0" in script)
        check("Expected terminal claim state" in script)
        check("Expected policy version 1" in script)
        check("CAPACITY_EXHAUSTED" in script)
        check("STALE_REVISION" in script)
        check("CAMPAIGN_PAUSED" in script)
        check("requestConfirmation" in script)
        check("/operator/api/v1/reviews?status=OPEN" in script)
        check("/operator/api/v1/reconciliation/backlog" in script)
        check("expectedReviewRevision" in script)
        check("/operator/api/v1/fixtures/reset" in script)
    }

    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        check(resource.exists()) { "static resource is missing: $name" }
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
