package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** bounded correlation id, browser hardening header, secret-free access log를 추가합니다. */
/** bounded correlation id, browser hardening header, secret-free access log를 추가합니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class RequestLoggingFilter: OncePerRequestFilter() {

    companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val CSP =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                    "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
    }
    
    /**
     * doFilterInternal은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param response 업무 처리에 필요한 response 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param filterChain 업무 처리에 필요한 filterChain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)?.takeIf(::isSafeRequestId) ?: newRequestId()
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)

        response.setHeader(REQUEST_ID_HEADER, requestId)
        response.setHeader("Content-Security-Policy", CSP)
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Cache-Control", "no-store")

        val started = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            log.info {
                "voucher_http_completed method=${request.method} path=${request.requestURI} " +
                        "status=${response.status} requestId=$requestId elapsedMillis=$elapsedMillis"
            }
        }
    }

    /**
     * isSafeRequestId은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun isSafeRequestId(value: String): Boolean =
        value.length in 1..64 && value.all { it.code in 0x21..0x7e }

}
