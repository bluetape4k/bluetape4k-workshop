@file:Suppress("MagicNumber")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import jakarta.servlet.AsyncContext
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.resource.NoResourceFoundException
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8

@RestControllerAdvice
/**
 * VoucherPoolExceptionHandler은 사전 생성 바우처 풀 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class VoucherPoolExceptionHandler {
    /**
     * `apiFailure`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = apiFailure(failure = currentVoucherFailure, request = currentHttpRequest)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(VoucherPoolApiException::class)
    fun apiFailure(failure: VoucherPoolApiException, request: HttpServletRequest): ResponseEntity<ApiError> =
        failure.response(request)

    /**
     * `jdbcTimeout`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = jdbcTimeout(failure = currentJdbcTimeout, request = currentHttpRequest)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(VoucherPoolJdbcTimeoutException::class)
    fun jdbcTimeout(
        failure: VoucherPoolJdbcTimeoutException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        log.warn {
            "voucher_pool_http_failed category=BACKEND_TIMEOUT lane=${failure.lane} phase=${failure.phase}"
        }
        return apiFailure(VoucherPoolErrorCode.BACKEND_TIMEOUT).response(request)
    }

    /**
     * `invalidInput`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = invalidInput(
     *     failure = IllegalArgumentException("invalid voucher code"),
     *     request = currentHttpRequest,
     * )
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request correlation id와 요청 메타데이터를 제공하는 현재 HTTP 요청입니다.
     * @return 400 `INVALID_REQUEST` 오류를 반환하고 원본 입력은 노출하지 않습니다.
     */
    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
        MethodArgumentTypeMismatchException::class,
    )
    /**
     * invalidInput은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * 예: 유효하지 않은 pool 요청은 `400`과 안정적인 오류 코드를 반환하며 voucher entry를 예약하지 않습니다.
     */
    fun invalidInput(failure: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.warn { "voucher_pool_http_rejected category=INVALID_REQUEST failure=${failure.javaClass.simpleName}" }
        request.allowJsonError()
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
            .body(invalidRequest().toApiError(request.requestId()))
    }

    /**
     * `missingResource`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = missingResource(failure = currentNotFound, request = currentHttpRequest)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun missingResource(failure: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.warn { "voucher_pool_http_rejected category=NOT_FOUND failure=${failure.javaClass.simpleName}" }
        request.allowJsonError()
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
            .body(resourceNotFound().toApiError(request.requestId()))
    }

    /**
     * `unexpected`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = unexpected(failure = currentException, request = currentHttpRequest)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        val failureTypes = generateSequence<Throwable>(failure) { it.cause }
            .take(MAX_LOGGED_CAUSE_DEPTH)
            .joinToString(">") { it.javaClass.simpleName }

        log.warn { "voucher_pool_http_failed category=INTERNAL_ERROR failureTypes=$failureTypes" }
        request.allowJsonError()

        return ResponseEntity
            .status(500)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ApiError("INTERNAL_ERROR", "request could not be completed", request.requestId()))
    }

    companion object: KLogging()
}

/**
 * `MAX_LOGGED_CAUSE_DEPTH`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MAX_LOGGED_CAUSE_DEPTH = 5

/**
 * response은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
 */
private fun VoucherPoolApiException.response(request: HttpServletRequest): ResponseEntity<ApiError> {
    request.allowJsonError()
    val builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
    retryAfterSeconds?.let { builder.header("Retry-After", it.toString()) }

    return builder.body(toApiError(request.requestId()))
}

/**
 * allowJsonError은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun HttpServletRequest.allowJsonError() {
    removeAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE)
}

/**
 * request body나 credential을 로그로 남기지 않고 bounded request id와 response hardening header를 추가합니다.
 * @property diagnostics 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class VoucherPoolRequestSecurityFilter(
    /**
     * `diagnostics`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val diagnostics: VoucherPoolDiagnosticRegistry,
): OncePerRequestFilter() {

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
                "voucher_pool_http_completed method=${request.method} path=${request.requestURI} " +
                        "status=${response.status} requestId=$requestId elapsedMillis=$elapsedMillis"
            }
            diagnostics.record(
                requestId = requestId,
                tenantCandidate = request.getHeader(TENANT_HEADER),
                method = request.method,
                path = request.requestURI,
                status = response.status,
                elapsedMillis = elapsedMillis,
            )
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

/**
 * controller deserialization과 application work 전에 transport-boundary value를 거부합니다.
 * @property properties 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property mapper 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
internal class VoucherPoolInputBoundaryFilter(
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
): OncePerRequestFilter() {

    companion object: KLogging()

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
        try {
            validateMetadata(request)
            val boundedRequest = if (request.contentLengthLong <= 0 && request.contentType == null) {
                request
            } else {
                BoundedPayloadRequestWrapper(request, response, properties.http.maxPayloadBytes)
            }
            filterChain.doFilter(boundedRequest, response)
        } catch (failure: VoucherPoolApiException) {
            if (response.isCommitted) {
                log.warn {
                    "voucher_pool_http_rejected_after_commit category=${failure.stableCode} " +
                            "requestId=${request.requestId()}"
                }
                throw failure
            }
            response.resetBuffer()
            mapper.writeApiError(response, failure, request.requestId())
        }
    }

    /**
     * validateMetadata은 프로모션 바우처 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun validateMetadata(request: HttpServletRequest) {
        val http = properties.http
        if (request.contentLengthLong > http.maxPayloadBytes) throw invalidRequest()
        request.getHeader(IDEMPOTENCY_HEADER)?.let {
            requireBoundedAscii(it, http.minIdempotencyKeyLength, http.maxIdempotencyKeyLength)
        }
        request.queryString?.split('&')?.forEach { parameter ->
            val separator = parameter.indexOf('=')
            val name = decodeQueryComponent(parameter.substring(0, separator.takeIf { it >= 0 } ?: parameter.length))
            val value = decodeQueryComponent(if (separator >= 0) parameter.substring(separator + 1) else "")
            val maxBytes = if (name == "cursor") http.maxCursorBytes else http.maxScalarBytes
            requireBoundedUtf8(value, maxBytes)
        }
    }

    /**
     * decodeQueryComponent은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를
     * 업무 오류로
     * 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun decodeQueryComponent(value: String): String =
        runCatching { URLDecoder.decode(value, UTF_8) }.getOrElse { throw invalidRequest() }

}
/**
 * BoundedPayloadRequestWrapper은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property response 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */


private class BoundedPayloadRequestWrapper(
    request: HttpServletRequest,
    /**
     * `response`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val response: HttpServletResponse,
    maxPayloadBytes: Int,
): HttpServletRequestWrapper(request) {

    companion object: KLogging()
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */

    private val originalRequest = request
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val boundedInputStream by lazy {
        BoundedServletInputStream(originalRequest.inputStream, maxPayloadBytes.toLong())
    }
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private var inputStreamRequested = false
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private var readerRequested = false
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private var boundedReader: BufferedReader? = null

    /**
     * getInputStream은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun getInputStream(): ServletInputStream {
        check(!readerRequested) { "getReader() has already been called for this request" }
        inputStreamRequested = true
        return boundedInputStream
    }

    /**
     * getReader은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun getReader(): BufferedReader {
        check(!inputStreamRequested) { "getInputStream() has already been called for this request" }
        readerRequested = true
        return boundedReader ?: BufferedReader(
            InputStreamReader(boundedInputStream, characterEncoding?.let(Charset::forName) ?: UTF_8),
        ).also { boundedReader = it }
    }

    /**
     * startAsync은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun startAsync(): AsyncContext =
        originalRequest.startAsync(this, response)

    /**
     * startAsync은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param servletRequest 프로모션 바우처 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param servletResponse 업무 처리에 필요한 servletResponse 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun startAsync(
        servletRequest: ServletRequest,
        servletResponse: ServletResponse,
    ): AsyncContext =
        originalRequest.startAsync(servletRequest, servletResponse)
}
/**
 * BoundedServletInputStream은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property delegate 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property maxBytes 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 */


private class BoundedServletInputStream(
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val delegate: ServletInputStream,
    /**
     * `maxBytes`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val maxBytes: Long,
): ServletInputStream() {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private var consumed = 0L

    /**
     * read은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) recordRead(1)
        return value
    }

    /**
     * read은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param offset 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param length 업무 처리에 필요한 length 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val probeLength = minOf(length.toLong(), maxBytes - consumed + 1L).coerceAtLeast(1L).toInt()
        val read = delegate.read(bytes, offset, probeLength)
        if (read > 0) recordRead(read.toLong())
        return read
    }

    /**
     * isFinished은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun isFinished(): Boolean = delegate.isFinished

    /**
     * isReady은 프로모션 바우처의 의존성·복구 상태를 계산해 트래픽을 받아도 되는지와 운영자에게 노출할 장애 수준을 구분합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun isReady(): Boolean = delegate.isReady

    /**
     * setReadListener은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param readListener 업무 처리에 필요한 readListener 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun setReadListener(readListener: ReadListener?) {
        delegate.setReadListener(readListener)
    }

    /**
     * recordRead은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param read 업무 처리에 필요한 read 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun recordRead(read: Long) {
        consumed += read
        if (consumed > maxBytes) throw invalidRequest()
    }
}

/**
 * requestId은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun HttpServletRequest.requestId(): String =
    getAttribute(REQUEST_ID_ATTRIBUTE) as? String ?: newRequestId()

/**
 * toApiError은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
internal fun VoucherPoolApiException.toApiError(requestId: String): ApiError =
    ApiError(stableCode, safeReason, requestId, retryAfterSeconds, effectId)

/**
 * writeApiError은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param response 업무 처리에 필요한 response 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param requestId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
internal fun ObjectMapper.writeApiError(
    response: HttpServletResponse,
    failure: VoucherPoolApiException,
    requestId: String,
) {
    response.status = failure.status
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    writeValue(response.outputStream, failure.toApiError(requestId))
}
