package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** reservation 및 owner-specific browser state가 cache되거나 다른 origin에 embedded되지 못하게 합니다. */
/** reservation 및 owner-specific browser state가 cache되거나 다른 origin에 embedded되지 못하게 합니다. */
@Component
internal class SecurityHeadersFilter: OncePerRequestFilter() {

    companion object: KLogging()

    /**
     * doFilterInternal은 예약 제어면의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 예약 제어면 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param response 업무 처리에 필요한 response 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param chain 업무 처리에 필요한 chain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("X-Content-Type-Options", "nosniff")

        chain.doFilter(request, response)

        log.debug { "reservation_security_headers_applied status=${response.status}" }
    }


}
