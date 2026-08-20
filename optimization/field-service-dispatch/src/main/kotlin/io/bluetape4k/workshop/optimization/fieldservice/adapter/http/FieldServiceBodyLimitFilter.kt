package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletRequestWrapper
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Mutation body를 선언 길이와 실제 입력 스트림 모두에서 256 KiB로 제한합니다. */
internal class FieldServiceBodyTooLargeException : IOException("request body is too large")

@Component
@Profile("demo")
internal class FieldServiceBodyLimitFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method != "POST" || !request.requestURI.startsWith("/api/field-service")) {
            filterChain.doFilter(request, response)
            return
        }

        if (request.contentLengthLong > FieldServiceLimits.MAX_BODY_BYTES) {
            writeTooLarge(response)
            return
        }
        try {
            filterChain.doFilter(BoundedBodyRequest(request, FieldServiceLimits.MAX_BODY_BYTES.toLong()), response)
        } catch (_: FieldServiceBodyTooLargeException) {
            if (!response.isCommitted) writeTooLarge(response)
        }
    }

    private fun writeTooLarge(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
        response.contentType = "application/json"
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write("{\"status\":413,\"code\":\"BODY_TOO_LARGE\",\"message\":\"request body is too large\"}")
    }

    private class BoundedBodyRequest(
        request: HttpServletRequest,
        private val maximumBytes: Long,
    ) : HttpServletRequestWrapper(request) {
        private val boundedInput = BoundedInputStream(super.getInputStream(), maximumBytes)

        override fun getInputStream(): ServletInputStream = boundedInput

        override fun getReader(): BufferedReader = BufferedReader(
            InputStreamReader(
                boundedInput,
                characterEncoding?.let(CharsetLookup::lookup) ?: StandardCharsets.UTF_8,
            ),
        )
    }

    private class BoundedInputStream(
        private val delegate: ServletInputStream,
        private val maximumBytes: Long,
    ) : ServletInputStream() {
        private var consumed = 0L

        private fun count(read: Int): Int {
            if (read > 0) {
                consumed += read
                if (consumed > maximumBytes) throw FieldServiceBodyTooLargeException()
            }
            return read
        }

        override fun read(): Int = count(delegate.read())

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = count(delegate.read(buffer, offset, length))

        override fun isFinished(): Boolean = delegate.isFinished

        override fun isReady(): Boolean = delegate.isReady

        override fun setReadListener(listener: ReadListener) {
            delegate.setReadListener(listener)
        }
    }

    private object CharsetLookup {
        fun lookup(name: String) = java.nio.charset.Charset.forName(name)
    }
}
