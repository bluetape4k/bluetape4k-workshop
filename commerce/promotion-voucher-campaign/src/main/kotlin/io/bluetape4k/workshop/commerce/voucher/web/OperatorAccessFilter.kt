package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.config.VoucherProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.security.MessageDigest

/**
 * operator controller가 호출되기 전에 명시적인 loopback demo trust boundary를 강제합니다.
 * @property properties 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property mapper 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
internal class OperatorAccessFilter(
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: VoucherProperties,
    /**
     * `mapper`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: ObjectMapper,
): OncePerRequestFilter() {

    companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val SAFE_METHODS = setOf("GET", "HEAD")
    }

    /**
     * shouldNotFilter은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/operator/")

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
        if (!allowed(request)) {
            log.warn { "voucher_operator_access_denied reason=TRUST_BOUNDARY requestId=${request.requestId()}" }
            response.status = 403
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            mapper.writeValue(
                response.outputStream,
                ApiError("OPERATOR_ACCESS_DENIED", "operator access denied", request.requestId()),
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    /**
     * allowed은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun allowed(request: HttpServletRequest): Boolean {
        if (request.method.equals("OPTIONS", ignoreCase = true)) return false
        val http = properties.http
        if (request.remoteAddr !in LOOPBACK_ADDRESSES) return false
        if (request.serverName.lowercase() !in http.allowedHosts.map(String::lowercase)) return false
        if (!constantTimeEquals(request.getHeader(OPERATOR_SECRET_HEADER), http.operatorSecret)) return false
        if (!constantTimeEquals(request.getHeader(OPERATOR_GUARD_HEADER), http.operatorGuard)) return false

        runCatching {
            requireAsciiIdentifier(
                request.getHeader(TENANT_HEADER),
                TENANT_HEADER
            )
        }.onFailure {
            return false
        }
        if (!sameOrigin(request)) return false
        if (
            request.method !in SAFE_METHODS &&
            request.contentType?.startsWith(MediaType.APPLICATION_JSON_VALUE) != true
        ) {
            return false
        }
        return true
    }

    /**
     * sameOrigin은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun sameOrigin(request: HttpServletRequest): Boolean {
        request.getHeader("Origin")?.let { return matchesServerOrigin(it, request) }
        if (request.method !in SAFE_METHODS) return false
        val explicitOrigin = request.getHeader("X-Workshop-Origin") ?: return false

        return matchesServerOrigin(explicitOrigin, request)
    }

    /**
     * matchesServerOrigin은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun matchesServerOrigin(
        value: String,
        request: HttpServletRequest,
    ): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val originPort = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80

        return uri.scheme in setOf("http", "https") &&
                uri.host.equals(request.serverName, ignoreCase = true) &&
                originPort == request.serverPort
    }

    /**
     * constantTimeEquals은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param actual 업무 처리에 필요한 actual 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expected 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun constantTimeEquals(
        actual: String?,
        expected: String,
    ): Boolean {
        val actualBytes = actual?.toUtf8Bytes() ?: ByteArray(0)
        return MessageDigest.isEqual(actualBytes, expected.toUtf8Bytes())
    }

    /**
     * requestId은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun HttpServletRequest.requestId(): String =
        getAttribute(REQUEST_ID_ATTRIBUTE) as? String ?: "unavailable"

}
