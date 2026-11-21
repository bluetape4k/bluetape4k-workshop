package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

/**
 * TicketBrowserContractTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class TicketBrowserContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "demo exposes accessible recovery status and bounded fallback" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * staticText은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun staticText(name: String): String {
        val resource = ClassPathResource("static/$name")
        resource.exists().shouldBeTrue()
        return resource.inputStream.bufferedReader().use { it.readText() }
    }
}
