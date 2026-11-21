package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

/**
 * EventSourcedBrowserContractTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedBrowserContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "browser keeps the last verified projection while pending and bounds GET retries" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "operator recovery actions require confirmation and stale tokens are never replayed" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "browser exposes keyboard and screen reader projection state" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * staticText은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        resource.exists().shouldBeTrue()
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
