package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

internal class EventSourcedBrowserContractTest {
    @Test
    fun `browser keeps the last verified projection while pending and bounds GET retries`() {
        val script = staticText("app.js")

        listOf(
            "MAX_PROJECTION_RETRIES = 5",
            "X-Min-Stream-Position",
            "PROJECTION_PENDING",
            "lastVerifiedProjection",
            "manual-refresh",
            "projection-lag-banner",
            "pending before generic success",
        ).all(script::contains).shouldBeTrue()
    }

    @Test
    fun `operator recovery actions require confirmation and stale tokens are never replayed`() {
        val script = staticText("app.js")

        listOf(
            "confirm-destructive-action",
            "X-Expected-Generation-Token",
            "status === 412",
            "requiresFreshConfirmation",
            "retryPoison",
            "startRebuild",
            "runReconciliation",
            "disabled-action-reason",
            "buildOperatorAction",
            "action.idempotencyKey",
            "action.expectedToken",
            "setActionBusy",
            "X-Workshop-Operator-Secret",
            "X-Workshop-Origin",
        ).all(script::contains).shouldBeTrue()
    }

    @Test
    fun `browser exposes keyboard and screen reader projection state`() {
        val html = staticText("index.html")
        val styles = staticText("styles.css")

        listOf(
            "aria-live=\"polite\"",
            "aria-atomic=\"true\"",
            "id=\"projection-status\"",
            "id=\"manual-refresh\"",
            "id=\"confirm-destructive-action\"",
            "id=\"disabled-action-reason\"",
            "id=\"projection-generation\"",
            "id=\"expected-generation-token\"",
            "id=\"poison-event-id\"",
        ).all(html::contains).shouldBeTrue()
        listOf(":focus-visible", ".sr-only", "content: \"Status: \"").all(styles::contains).shouldBeTrue()
    }

    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        resource.exists().shouldBeTrue()
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
