package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

internal class VoucherBrowserContractTest {
    @Test
    fun `static console contains keyboard live status and secret handling contracts`() {
        val html = staticText("index.html")
        html shouldContain "aria-live=\"polite\""
        html shouldContain "aria-label="
        html shouldContain "type=\"password\""
        html shouldContain "id=\"run-scenario\""
        html shouldContain "id=\"reset-scenario\""
        html shouldContain "id=\"review-list\""
        html shouldContain "id=\"reconciliation-list\""
        html shouldContain "id=\"refresh-operator-evidence\""
        html shouldContain "delayed-duplicate-out-of-order"

        val script = staticText("app.js")
        script shouldNotContain "innerHTML"
        script shouldContain "textContent"
        script shouldContain "restoreFocus"
        script shouldNotContain "localStorage"
        script shouldNotContain "document.cookie"
        script shouldContain "clearOperatorSecret"
        script shouldNotContain "history."
        script shouldContain "alternateSnapshotPath"
        script shouldContain "alternate.origin !== location.origin"
        script shouldContain "fallbackAttempts > 10"
        script shouldContain "operator-disabled-reason"
        script shouldContain "X-Workshop-Origin"
        script shouldContain "executeScenario"
        script shouldContain "Promise.allSettled"
        script shouldContain "Expected exactly one terminal winner"
        script shouldContain "Expected remaining capacity 0"
        script shouldContain "Expected terminal claim state"
        script shouldContain "Expected policy version 1"
        script shouldContain "CAPACITY_EXHAUSTED"
        script shouldContain "STALE_REVISION"
        script shouldContain "CAMPAIGN_PAUSED"
        script shouldContain "requestConfirmation"
        script shouldContain "/operator/api/v1/reviews?status=OPEN"
        script shouldContain "/operator/api/v1/reconciliation/backlog"
        script shouldContain "expectedReviewRevision"
        script shouldContain "/operator/api/v1/fixtures/reset"
    }

    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        resource.exists().shouldBeTrue()
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
