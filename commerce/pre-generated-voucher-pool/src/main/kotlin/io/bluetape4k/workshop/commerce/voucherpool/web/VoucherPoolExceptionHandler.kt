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
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.ServletInputStream
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.resource.NoResourceFoundException
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8

@RestControllerAdvice
internal class VoucherPoolExceptionHandler {
    @ExceptionHandler(VoucherPoolApiException::class)
    fun apiFailure(failure: VoucherPoolApiException, request: HttpServletRequest): ResponseEntity<ApiError> =
        failure.response(request)

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

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalidInput(failure: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.warn { "voucher_pool_http_rejected category=INVALID_REQUEST failure=${failure.javaClass.simpleName}" }
        request.allowJsonError()
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
            .body(invalidRequest().toApiError(request.requestId()))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun missingResource(failure: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.warn { "voucher_pool_http_rejected category=NOT_FOUND failure=${failure.javaClass.simpleName}" }
        request.allowJsonError()
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
            .body(resourceNotFound().toApiError(request.requestId()))
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(failure: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        val failureTypes = generateSequence<Throwable>(failure) { it.cause }
            .take(MAX_LOGGED_CAUSE_DEPTH)
            .joinToString(">") { it.javaClass.simpleName }
        log.warn { "voucher_pool_http_failed category=INTERNAL_ERROR failureTypes=$failureTypes" }
        request.allowJsonError()
        return ResponseEntity.status(500).contentType(MediaType.APPLICATION_JSON).body(
            ApiError("INTERNAL_ERROR", "request could not be completed", request.requestId()),
        )
    }

    companion object : KLogging()
}

private const val MAX_LOGGED_CAUSE_DEPTH = 5

private fun VoucherPoolApiException.response(request: HttpServletRequest): ResponseEntity<ApiError> {
    request.allowJsonError()
    val builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
    retryAfterSeconds?.let { builder.header("Retry-After", it.toString()) }
    return builder.body(toApiError(request.requestId()))
}

private fun HttpServletRequest.allowJsonError() {
    removeAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE)
}

/** request body나 credential을 로그로 남기지 않고 bounded request id와 response hardening header를 추가합니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class VoucherPoolRequestSecurityFilter(
    private val diagnostics: VoucherPoolDiagnosticRegistry,
) : OncePerRequestFilter() {
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
                requestId,
                request.getHeader(TENANT_HEADER),
                request.method,
                request.requestURI,
                response.status,
                elapsedMillis,
            )
        }
    }

    private fun isSafeRequestId(value: String): Boolean =
        value.length in 1..64 && value.all { it.code in 0x21..0x7e }

    companion object : KLogging() {
        private const val CSP =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
    }
}

/** controller deserialization과 application work 전에 transport-boundary value를 거부합니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
internal class VoucherPoolInputBoundaryFilter(
    private val properties: VoucherPoolProperties,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {
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

    private fun decodeQueryComponent(value: String): String =
        runCatching { URLDecoder.decode(value, UTF_8) }.getOrElse { throw invalidRequest() }

    companion object : KLogging()
}

private class BoundedPayloadRequestWrapper(
    request: HttpServletRequest,
    private val response: HttpServletResponse,
    maxPayloadBytes: Int,
) : HttpServletRequestWrapper(request) {
    private val originalRequest = request
    private val boundedInputStream by lazy {
        BoundedServletInputStream(originalRequest.inputStream, maxPayloadBytes.toLong())
    }
    private var inputStreamRequested = false
    private var readerRequested = false
    private var boundedReader: BufferedReader? = null

    override fun getInputStream(): ServletInputStream {
        check(!readerRequested) { "getReader() has already been called for this request" }
        inputStreamRequested = true
        return boundedInputStream
    }

    override fun getReader(): BufferedReader {
        check(!inputStreamRequested) { "getInputStream() has already been called for this request" }
        readerRequested = true
        return boundedReader ?: BufferedReader(
            InputStreamReader(boundedInputStream, characterEncoding?.let(Charset::forName) ?: UTF_8),
        ).also { boundedReader = it }
    }

    override fun startAsync(): AsyncContext = originalRequest.startAsync(this, response)

    override fun startAsync(
        servletRequest: ServletRequest,
        servletResponse: ServletResponse,
    ): AsyncContext = originalRequest.startAsync(servletRequest, servletResponse)
}

private class BoundedServletInputStream(
    private val delegate: ServletInputStream,
    private val maxBytes: Long,
) : ServletInputStream() {
    private var consumed = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) recordRead(1)
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val probeLength = minOf(length.toLong(), maxBytes - consumed + 1L).coerceAtLeast(1L).toInt()
        val read = delegate.read(bytes, offset, probeLength)
        if (read > 0) recordRead(read.toLong())
        return read
    }

    override fun isFinished(): Boolean = delegate.isFinished

    override fun isReady(): Boolean = delegate.isReady

    override fun setReadListener(readListener: ReadListener?) {
        delegate.setReadListener(readListener)
    }

    private fun recordRead(read: Long) {
        consumed += read
        if (consumed > maxBytes) throw invalidRequest()
    }
}

internal fun HttpServletRequest.requestId(): String =
    getAttribute(REQUEST_ID_ATTRIBUTE) as? String ?: newRequestId()

internal fun VoucherPoolApiException.toApiError(requestId: String): ApiError =
    ApiError(stableCode, safeReason, requestId, retryAfterSeconds, effectId)

internal fun ObjectMapper.writeApiError(
    response: HttpServletResponse,
    failure: VoucherPoolApiException,
    requestId: String,
) {
    response.status = failure.status
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    writeValue(response.outputStream, failure.toApiError(requestId))
}
