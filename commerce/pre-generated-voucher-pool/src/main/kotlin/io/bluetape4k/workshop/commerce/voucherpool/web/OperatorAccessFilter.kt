package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionNamespace
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionGate
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.security.MessageDigest

/**
 * operator route가 dispatch되기 전에 local operator trust boundary를 적용합니다.
 * @property properties 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property mapper 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property admissionGate 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property environment 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
@Order(OPERATOR_FILTER_ORDER)
internal class OperatorAccessFilter(
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: VoucherPoolProperties,
    /**
     * `mapper`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: ObjectMapper,
    /**
     * `admissionGate`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val admissionGate: VoucherPoolAdmissionGate,
    /**
     * `environment`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val environment: Environment,
): OncePerRequestFilter() {
    /**
     * 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
     */
    private val credentialVerifier = OperatorCredentialVerifier()

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
            log.warn { "voucher_pool_operator_access_denied requestId=${request.requestId()}" }
            mapper.writeApiError(response, resourceNotFound(), request.requestId())
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
    @Suppress("ReturnCount")
    private fun allowed(request: HttpServletRequest): Boolean {
        val http = properties.http
        val tenantCandidate = request.getHeader(TENANT_HEADER)
        if (!admitted(request.remoteAddr)) return false
        if (request.method.equals("OPTIONS", ignoreCase = true)) return false
        if (!environment.getProperty("server.address", "127.0.0.1").isLoopbackHost()) return false
        if (!request.remoteAddr.isLoopbackHost()) return false
        if (request.serverName.lowercase() !in http.allowedHosts.map(String::lowercase)) return false

        if (!credentialVerifier.matches(
                request.getHeader(OPERATOR_SECRET_HEADER),
                http.operatorSecret,
                request.getHeader(OPERATOR_GUARD_HEADER),
                http.operatorGuard,
            )
        ) {
            return false
        }
        if (runCatching { requireBoundedAscii(tenantCandidate, 1, http.maxTenantLength) }.isFailure) return false
        if (!sameOrigin(request)) return false
        val hasJsonContentType = request.contentType?.startsWith(MediaType.APPLICATION_JSON_VALUE) == true
        if (request.method !in SAFE_METHODS && !hasJsonContentType) {
            return false
        }
        return true
    }

    /**
     * admitted은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param remoteAddress 업무 처리에 필요한 remoteAddress 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun admitted(remoteAddress: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(remoteAddress.toUtf8Bytes())
        return admissionGate.admit(AdmissionNamespace.OPERATOR_AUTH, digest) in ADMITTED_DECISIONS
    }

    /**
     * sameOrigin은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    @Suppress("ReturnCount")
    private fun sameOrigin(request: HttpServletRequest): Boolean {
        request.getHeader("Origin")?.let { return matchesServerOrigin(it, request) }
        if (request.method !in SAFE_METHODS) return false
        return request.getHeader("X-Workshop-Origin")?.let { matchesServerOrigin(it, request) } ?: false
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
    @Suppress("ComplexCondition", "ReturnCount")
    private fun matchesServerOrigin(value: String, request: HttpServletRequest): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val containsNonOriginParts =
            uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.path !in ORIGIN_PATHS
        if (containsNonOriginParts) return false
        val originPort = if (uri.port >= 0) uri.port else if (uri.scheme == "https") HTTPS_PORT else HTTP_PORT
        return uri.scheme.equals(request.scheme, ignoreCase = true) &&
                uri.host.equals(request.serverName, ignoreCase = true) &&
                originPort == request.serverPort
    }

    companion object: KLogging() {
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val SAFE_METHODS = setOf("GET", "HEAD")
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val ORIGIN_PATHS = setOf("", "/")
        /**
         * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val ADMITTED_DECISIONS = setOf(AdmissionDecision.ALLOW, AdmissionDecision.DEGRADED_ALLOW)
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val HTTP_PORT = 80
        /**
         * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val HTTPS_PORT = 443
    }
}

/**
 * `OPERATOR_FILTER_ORDER`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val OPERATOR_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10

/**
 * FixedLengthDigestComparator은 사전 생성 바우처 풀 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface FixedLengthDigestComparator {
    /**
     * matches은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param actualDigest 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param expectedDigest 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun matches(actualDigest: ByteArray, expectedDigest: ByteArray): Boolean
}

/**
 * missing 및 variable-length input도 고정 comparison shape를 공유하도록 비교 전에 두 credential을 모두 hash합니다.
 * @property comparator 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class OperatorCredentialVerifier(
    /**
     * `comparator`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val comparator: FixedLengthDigestComparator = FixedLengthDigestComparator(MessageDigest::isEqual),
) {
    /**
     * matches은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param actualSecret 업무 처리에 필요한 actualSecret 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedSecret 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param actualGuard 업무 처리에 필요한 actualGuard 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedGuard 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    fun matches(
        actualSecret: String?,
        expectedSecret: String,
        actualGuard: String?,
        expectedGuard: String,
    ): Boolean {
        val secretMatches = comparator.matches(digest(actualSecret), digest(expectedSecret))
        val guardMatches = comparator.matches(digest(actualGuard), digest(expectedGuard))

        return secretMatches && guardMatches
    }

    /**
     * digest은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun digest(value: String?): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.orEmpty().toUtf8Bytes())
}
