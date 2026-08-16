package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.charset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionHttpResponse
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.application.JobSubmissionHttpMapper
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.ktor.http.HttpStatusCode
import kotlinx.io.readByteArray
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.nio.charset.StandardCharsets.UTF_8

internal const val MAX_JOB_SUBMISSION_BODY_BYTES: Int = 64 * 1024

internal class KtorJobSubmissionRequestTooLargeException : RuntimeException("job submission body exceeds 64 KiB")

internal class KtorJobSubmissionInvalidRequestException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class KtorJobSubmissionScopeDeniedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal object JobConsoleKtorSubmissionHttp {
    private val strictMapper: JsonMapper =
        JsonMapper.builder(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxDocumentLength(MAX_JOB_SUBMISSION_BODY_BYTES.toLong())
                        .maxNestingDepth(32)
                        .maxStringLength(MAX_JOB_SUBMISSION_BODY_BYTES)
                        .maxNameLength(256)
                        .maxTokenCount(256)
                        .build(),
                ).build(),
        ).addModule(kotlinModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build()

    fun scope(call: ApplicationCall): DemoCallerScope {
        val tenant = requiredSingleHeader(call, "X-Demo-Tenant", scopeHeader = true)
        val submitter = requiredSingleHeader(call, "X-Demo-Submitter", scopeHeader = true)
        return try {
            DemoCallerScope(tenant, submitter)
        } catch (failure: IllegalArgumentException) {
            throw KtorJobSubmissionScopeDeniedException("invalid demo caller scope", failure)
        }
    }

    fun idempotencyKey(call: ApplicationCall): String {
        val key = requiredSingleHeader(call, "Idempotency-Key", scopeHeader = false)
        val bytes = key.toByteArray(UTF_8)
        if (bytes.isEmpty() || bytes.size > 255 || bytes.any { it.toInt() !in 0x21..0x7e } || ',' in key) {
            throw KtorJobSubmissionInvalidRequestException("Idempotency-Key must be printable ASCII, 1..255 bytes, and contain no comma")
        }
        return key
    }

    @OptIn(InternalAPI::class)
    suspend fun readSubmitRequest(call: ApplicationCall): SubmitJobRequest {
        validateContentType(call.request.contentType())
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_JOB_SUBMISSION_BODY_BYTES) {
            throw KtorJobSubmissionRequestTooLargeException()
        }
        try {
            val bytes = call.receiveChannel().readRemaining((MAX_JOB_SUBMISSION_BODY_BYTES + 1L)).readByteArray()
            if (bytes.size > MAX_JOB_SUBMISSION_BODY_BYTES) throw KtorJobSubmissionRequestTooLargeException()
            return strictMapper.readValue(bytes, SubmitJobRequest::class.java)
        } catch (failure: KtorJobSubmissionRequestTooLargeException) {
            throw failure
        } catch (failure: Exception) {
            throw KtorJobSubmissionInvalidRequestException("invalid job submission JSON", failure)
        }
    }

    private fun requiredSingleHeader(call: ApplicationCall, name: String, scopeHeader: Boolean): String {
        val values = call.request.headers.getAll(name).orEmpty()
        if (values.size != 1 || values.single().isBlank()) {
            if (scopeHeader) throw KtorJobSubmissionScopeDeniedException("exactly one non-blank $name header is required")
            throw KtorJobSubmissionInvalidRequestException("exactly one non-blank $name header is required")
        }
        return values.single()
    }

    private fun validateContentType(contentType: ContentType) {
        if (!contentType.contentType.equals("application", ignoreCase = true) ||
            !contentType.contentSubtype.equals("json", ignoreCase = true) ||
            (contentType.charset() != null && contentType.charset() != UTF_8)
        ) {
            throw KtorJobSubmissionInvalidRequestException("Content-Type must be application/json with UTF-8")
        }
    }
}

internal suspend fun ApplicationCall.respondSubmission(response: JobSubmissionHttpResponse) {
    response.headers.forEach { (name, values) -> values.forEach { this.response.headers.append(name, it) } }
    respondBytes(
        bytes = response.body,
        contentType = ContentType.parse(response.contentType),
        status = HttpStatusCode.fromValue(response.status),
    )
}

internal suspend fun ApplicationCall.respondSubmissionProblem(response: JobSubmissionHttpResponse) = respondSubmission(response)
