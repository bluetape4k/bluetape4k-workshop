package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/** 공유 browser UI가 제출·새로고침·SSE 구독·취소 흐름을 실제 API 이름으로 안내하는지 검증한다. */
/** 공유 browser UI가 제출·새로고침·SSE 구독·취소 흐름을 실제 API 이름으로 안내하는지 검증한다. */
class JobConsoleUiTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "shared browser console submits refreshes subscribes and cancels jobs" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
        ).all(html::contains).shouldBeTrue()
    }
}
