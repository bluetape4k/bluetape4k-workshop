package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/** 명시적인 metadata allowlist만 로그로 남깁니다. header, identity, key, body, query string은 제외합니다. */
/** 명시적인 metadata allowlist만 로그로 남깁니다. header, identity, key, body, query string은 제외합니다. */
class RequestLoggingFilter: OncePerRequestFilter() {

    companion object: KLogging()

    /**
     * doFilterInternal은 콘서트 티켓 판매의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 콘서트 티켓 판매 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param response 업무 처리에 필요한 response 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param chain 업무 처리에 필요한 chain 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val correlationId = request.getHeader("X-Correlation-Id")?.takeIf { it.length in 8..64 }
            ?: Uuid.V7.nextId().toString()

        response.setHeader("X-Correlation-Id", correlationId)
        try {
            chain.doFilter(request, response)
        } finally {
            log.info {
                "ticket_http method=${request.method} path=${request.requestURI} status=${response.status} correlationId=$correlationId"
            }
        }
    }
}
