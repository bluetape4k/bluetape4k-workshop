package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class JobConsoleUiTest {
    @Test
    fun `shared browser console submits refreshes subscribes and cancels jobs`() {
        val html = JobConsoleUi.indexHtml

        listOf(
            "id=\"submitJob\"",
            "id=\"cancelJob\"",
            "id=\"position\"",
            "id=\"jobsAhead\"",
            "id=\"etaRange\"",
            "id=\"etaConfidence\"",
            "id=\"progress\"",
            "id=\"checkpoint\"",
            "id=\"cancelAcknowledgement\"",
            "api('/v1/jobs'",
            "response.body.getReader()",
            "refreshSnapshot",
        ).all(html::contains) shouldBeEqualTo true
    }
}
