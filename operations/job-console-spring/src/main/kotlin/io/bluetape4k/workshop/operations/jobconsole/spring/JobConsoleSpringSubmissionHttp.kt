package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.workshop.operations.jobconsole.api.JobConsoleJson
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import org.springframework.http.MediaType
import tools.jackson.databind.json.JsonMapper
import jakarta.servlet.http.HttpServletRequest
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Collections

internal const val MAX_JOB_SUBMISSION_BODY_BYTES: Int = 64 * 1024

internal class JobSubmissionRequestTooLargeException : RuntimeException("job submission body exceeds 64 KiB")

internal class JobSubmissionInvalidRequestException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class JobSubmissionScopeDeniedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal object JobConsoleSpringSubmissionHttp {
    private val strictMapper: JsonMapper = JobConsoleJson.strictRequestMapper(MAX_JOB_SUBMISSION_BODY_BYTES)

    fun scope(request: HttpServletRequest): io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope {
        val tenant = runCatching { request.singleHeader("X-Demo-Tenant") }
            .getOrElse { throw JobSubmissionScopeDeniedException("X-Demo-Tenant is required", it) }
        val submitter = runCatching { request.singleHeader("X-Demo-Submitter") }
            .getOrElse { throw JobSubmissionScopeDeniedException("X-Demo-Submitter is required", it) }
        return try {
            io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope(tenant, submitter)
        } catch (failure: IllegalArgumentException) {
            throw JobSubmissionScopeDeniedException("invalid demo caller scope", failure)
        }
    }

    fun idempotencyKey(request: HttpServletRequest): String {
        val key = request.singleHeader("Idempotency-Key")
        val bytes = key.toByteArray(UTF_8)
        if (bytes.isEmpty() || bytes.size > 255 || bytes.any { it.toInt() !in 0x21..0x7e } || ',' in key) {
            throw JobSubmissionInvalidRequestException("Idempotency-Key must be printable ASCII, 1..255 bytes, and contain no comma")
        }
        return key
    }

    fun readSubmitRequest(request: HttpServletRequest): SubmitJobRequest {
        validateContentType(request.contentType)
        val contentLength = request.contentLengthLong
        if (contentLength > MAX_JOB_SUBMISSION_BODY_BYTES) throw JobSubmissionRequestTooLargeException()

        val body = ByteArrayOutputStream(contentLength.coerceIn(0, MAX_JOB_SUBMISSION_BODY_BYTES.toLong()).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        try {
            request.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_JOB_SUBMISSION_BODY_BYTES) throw JobSubmissionRequestTooLargeException()
                    body.write(buffer, 0, read)
                }
            }
            return strictMapper.readValue(body.toByteArray(), SubmitJobRequest::class.java)
        } catch (failure: JobSubmissionRequestTooLargeException) {
            throw failure
        } catch (failure: Exception) {
            throw JobSubmissionInvalidRequestException("invalid job submission JSON", failure)
        }
    }

    private fun HttpServletRequest.singleHeader(name: String): String {
        val values = Collections.list(getHeaders(name))
        if (values.size != 1 || values.single().isBlank()) {
            throw JobSubmissionInvalidRequestException("exactly one non-blank $name header is required")
        }
        return values.single()
    }

    private fun validateContentType(raw: String?) {
        if (raw.isNullOrBlank()) throw JobSubmissionInvalidRequestException("Content-Type must be application/json")
        val mediaType =
            try {
                MediaType.parseMediaType(raw)
            } catch (failure: IllegalArgumentException) {
                throw JobSubmissionInvalidRequestException("Content-Type must be application/json", failure)
            }
        if (!mediaType.type.equals("application", ignoreCase = true) ||
            !mediaType.subtype.equals("json", ignoreCase = true) ||
            (mediaType.charset != null && mediaType.charset != UTF_8)
        ) {
            throw JobSubmissionInvalidRequestException("Content-Type must be application/json with UTF-8")
        }
    }
}
