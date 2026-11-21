package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.*

/** command body나 credential을 기록하지 않고 HTTP boundary를 로그로 남깁니다. */
/** command body나 credential을 기록하지 않고 HTTP boundary를 로그로 남깁니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class RequestLoggingFilter: OncePerRequestFilter() {

    companion object: KLogging() {
        /**
         * 좌석 예약·대기열 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
         */
        private const val REQUEST_ID_HEADER = "X-Request-Id"
        /**
         * 좌석 예약·대기열 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
         */
        const val REQUEST_ID_ATTRIBUTE = "reservation.requestId"
        /**
         * 좌석 예약·대기열 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
         */
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._-]{8,80}")
    }

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
        val requestId = request
            .getHeader(REQUEST_ID_HEADER)
            ?.takeIf(REQUEST_ID_PATTERN::matches)
            ?: UUID.randomUUID().toString()

        response.setHeader(REQUEST_ID_HEADER, requestId)
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)
        val startedAt = System.nanoTime()

        try {
            chain.doFilter(request, response)
        } finally {
            val elapsedMillis = (System.nanoTime() - startedAt).millis()
            log.info {
                "reservation_http_completed requestId=$requestId method=${request.method} " +
                        "path=${request.requestURI} status=${response.status} elapsedMillis=$elapsedMillis"
            }
        }
    }
}
