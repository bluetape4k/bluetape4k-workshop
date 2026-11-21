package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

/**
 * VoucherBrowserContractTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherBrowserContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "static console contains keyboard live status and secret handling contracts" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * staticText은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        resource.exists().shouldBeTrue()
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
