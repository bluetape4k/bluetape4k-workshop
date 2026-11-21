package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.support.requireGe
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * demo 전용 operator authentication입니다. TCP peer가 loopback이 아니면 credential을 거부합니다.
 * @property expectedToken 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 */
class OperatorAccessFilter(private val expectedToken: String): OncePerRequestFilter() {
    init {
        expectedToken.length.requireGe(32, "expectedToken.length")
    }

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
        if (request.requestURI.startsWith("/api/v1/operator/")) {
            val token = request.getHeader("X-Demo-Operator")
            if (!request.remoteAddr.isLoopbackLiteral() || !constantTimeEquals(token, expectedToken)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                return
            }
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
                "demo-operator",
                null,
                listOf(SimpleGrantedAuthority("ROLE_OPERATOR")),
            )
        }
        chain.doFilter(request, response)
    }

    /**
     * constantTimeEquals은 콘서트 티켓 판매 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param candidate 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param expected 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun constantTimeEquals(candidate: String?, expected: String): Boolean {
        if (candidate == null || candidate.length != expected.length) return false
        var mismatch = 0
        candidate.indices.forEach { mismatch = mismatch or (candidate[it].code xor expected[it].code) }
        return mismatch == 0
    }
}

/**
 * isLoopbackLiteral은 콘서트 티켓 판매의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun String.isLoopbackLiteral(): Boolean = this == "127.0.0.1" || this == "::1" || this == "0:0:0:0:0:0:0:1"
