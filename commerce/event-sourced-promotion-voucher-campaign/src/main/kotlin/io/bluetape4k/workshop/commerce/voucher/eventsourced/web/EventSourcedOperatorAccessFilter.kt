package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.HmacPurpose
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.security.MessageDigest

/**
 * `OPERATOR_SECRET_HEADER`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
internal const val OPERATOR_SECRET_HEADER = "X-Workshop-Operator-Secret"
/**
 * `OPERATOR_GUARD_HEADER`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
internal const val OPERATOR_GUARD_HEADER = "X-Workshop-Guard"
/**
 * `OPERATOR_ROLE_HEADER`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
internal const val OPERATOR_ROLE_HEADER = "X-Workshop-Operator-Role"
/**
 * `OPERATOR_ACTOR_SURROGATE_ATTRIBUTE`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
internal const val OPERATOR_ACTOR_SURROGATE_ATTRIBUTE = "eventSourcedOperatorActorSurrogate"
/**
 * `FILTER_ORDER_OFFSET`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
private const val FILTER_ORDER_OFFSET = 10

@ConfigurationProperties("voucher.operator")
/**
 * EventSourcedOperatorProperties은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class EventSourcedOperatorProperties(
    secret: String,
    guard: String,
    allowedHosts: Set<String> = setOf("127.0.0.1", "localhost"),
) {
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    val secret: String = secret.requireNotBlank("voucher.operator.secret")
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val guard: String = guard.requireNotBlank("voucher.operator.guard")
    /**
     * 프로모션 바우처 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */
    val allowedHosts: Set<String> = allowedHosts
        .requireNotEmpty("voucher.operator.allowed-hosts")
        .mapTo(linkedSetOf()) { host ->
            host.requireNotBlank("voucher.operator.allowed-hosts").lowercase()
        }
}

/**
 * mutation-capable operator route를 위한 fail-closed workshop trust boundary입니다.
 * @property properties 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property mapper 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property hmacKeyRing 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + FILTER_ORDER_OFFSET)
internal class EventSourcedOperatorAccessFilter(
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: EventSourcedOperatorProperties,
    /**
     * `mapper`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: ObjectMapper,
    /**
     * `hmacKeyRing`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    private val hmacKeyRing: EventSourcedHmacKeyRing,
): OncePerRequestFilter() {
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
        if (!isAllowed(request)) {
            log.warn { "event_sourced_operator_access_denied reason=TRUST_BOUNDARY" }
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            mapper.writeValue(
                response.outputStream,
                EventSourcedApiError("OPERATOR_ACCESS_DENIED", "operator access denied"),
            )
            return
        }
        request.setAttribute(
            OPERATOR_ACTOR_SURROGATE_ATTRIBUTE,
            hmacKeyRing.digest(
                purpose = HmacPurpose.OPERATOR_ACTOR,
                tenantId = TenantId(request.singleHeader(TENANT_HEADER).requireNotNull(TENANT_HEADER)),
                domain = "operator-access",
                value = request.singleHeader(PRINCIPAL_HEADER).requireNotNull(PRINCIPAL_HEADER),
            ).value,
        )
        filterChain.doFilter(request, response)
    }

    /**
     * isAllowed은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun isAllowed(request: HttpServletRequest): Boolean =
        hasSingleValuedHeaders(request) &&
                request.method in ALLOWED_METHODS &&
                request.remoteAddr in LOOPBACK_ADDRESSES &&
                request.serverName.lowercase() in properties.allowedHosts &&
                constantTimeEquals(request.singleHeader(OPERATOR_SECRET_HEADER), properties.secret) &&
                constantTimeEquals(request.singleHeader(OPERATOR_GUARD_HEADER), properties.guard) &&
                request.singleHeader(OPERATOR_ROLE_HEADER) == "OPERATOR" &&
                !request.singleHeader(TENANT_HEADER).isNullOrBlank() &&
                !request.singleHeader(PRINCIPAL_HEADER).isNullOrBlank() &&
                sameOrigin(request) &&
                hasSafeContentType(request)

    /**
     * hasSingleValuedHeaders은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun hasSingleValuedHeaders(request: HttpServletRequest): Boolean =
        TRUST_HEADERS.all { header -> request.getHeaders(header).toList().size <= 1 }

    /**
     * hasSafeContentType은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun hasSafeContentType(request: HttpServletRequest): Boolean =
        request.method != "POST" || request.contentType?.startsWith(MediaType.APPLICATION_JSON_VALUE) == true

    /**
     * sameOrigin은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun sameOrigin(request: HttpServletRequest): Boolean {
        val origin = request.singleHeader("Origin") ?: request.singleHeader("X-Workshop-Origin")
        val uri = origin?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        val port = if (uri.port >= 0) uri.port else if (uri.scheme == "https") HTTPS_PORT else HTTP_PORT

        return uri.scheme in ALLOWED_SCHEMES &&
                uri.host.equals(request.serverName, ignoreCase = true) &&
                port == request.serverPort
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
    ): Boolean =
        MessageDigest.isEqual(
            actual?.toUtf8Bytes() ?: ByteArray(0),
            expected.toUtf8Bytes(),
        )

    private companion object: KLogging() {
        /**
         * `HTTP_PORT`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        const val HTTP_PORT = 80
        /**
         * `HTTPS_PORT`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        const val HTTPS_PORT = 443
        /**
         * `LOOPBACK_ADDRESSES`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
        /**
         * `ALLOWED_METHODS`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
         *
         * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
         */
        val ALLOWED_METHODS = setOf("GET", "HEAD", "POST")
        /**
         * `ALLOWED_SCHEMES`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
         *
         * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
         */
        val ALLOWED_SCHEMES = setOf("http", "https")
        /**
         * `TRUST_HEADERS`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val TRUST_HEADERS = setOf(
            OPERATOR_SECRET_HEADER,
            OPERATOR_GUARD_HEADER,
            OPERATOR_ROLE_HEADER,
            TENANT_HEADER,
            PRINCIPAL_HEADER,
            "Origin",
            "X-Workshop-Origin",
        )
    }
}

/**
 * singleHeader은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun HttpServletRequest.singleHeader(name: String): String? =
    getHeaders(name).toList().singleOrNull()
