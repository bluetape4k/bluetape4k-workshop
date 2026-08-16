package io.bluetape4k.workshop.operations.jobconsole.idempotency

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale

/** Validates and canonicalizes the bounded HTTP response snapshot before persistence. */
internal class JobSubmissionSnapshotPolicy private constructor(
    private val policy: JobSubmissionIdempotencyPolicy,
    private val acceptedStatuses: Set<Int>,
    private val acceptedContentTypes: Set<String>,
    private val replayHeaderAllowlist: Set<String> = emptySet(),
) {
    internal constructor(policy: JobSubmissionIdempotencyPolicy) :
        this(policy, setOf(202), setOf(APPLICATION_JSON))

    fun validate(prepared: PreparedJobSubmission): PreparedJobSubmission {
        require(prepared.responseStatus in acceptedStatuses) {
            if (acceptedStatuses == SYNTHETIC_STATUSES) {
                "synthetic response status must be 201 or 422"
            } else {
                "production replay response must be 202"
            }
        }
        require(prepared.responseBody.size <= policy.maxReplayBytes) { "response body exceeds replay limit" }
        require(prepared.responseContentType in acceptedContentTypes) {
            if (acceptedContentTypes == setOf(APPLICATION_JSON)) {
                "production replay content type must be application/json"
            } else {
                "response content type is not replayable"
            }
        }
        require(prepared.responseHeaders.size <= policy.maxHeaderNames) {
            "response header count exceeds replay limit"
        }

        val names = HashSet<String>(prepared.responseHeaders.size)
        val canonicalHeaders = linkedMapOf<String, List<String>>()
        var aggregateBytes = 0
        prepared.responseHeaders.forEach { (name, values) ->
            val canonicalName = name.lowercase(Locale.ROOT)
            require(HEADER_NAME.matches(canonicalName)) { "response header name must be an HTTP token" }
            require(names.add(canonicalName)) { "response header names must be unique" }
            require(canonicalName.length <= MAX_HEADER_NAME_LENGTH) {
                "response header name exceeds replay limit"
            }
            require(values.isNotEmpty() && values.size <= policy.maxHeaderValues) {
                "response header value count exceeds replay limit"
            }

            aggregateBytes += canonicalName.toByteArray(UTF_8).size
            val copiedValues = values.map { value ->
                val valueBytes = value.toByteArray(UTF_8)
                require(valueBytes.size <= policy.maxHeaderValueBytes) {
                    "response header value exceeds replay limit"
                }
                require(value.all { it.code in 0x20..0x7e }) {
                    "response header value contains a control character"
                }
                aggregateBytes += valueBytes.size
                value
            }
            require(aggregateBytes <= policy.maxAggregateHeaderBytes) {
                "response headers exceed aggregate replay limit"
            }
            require(canonicalName !in FORBIDDEN_REPLAY_HEADERS) { "response header is not replayable" }
            require(!SENSITIVE_HEADER_PATTERN.containsMatchIn(canonicalName)) {
                "response header is not replayable"
            }
            require(canonicalName in replayHeaderAllowlist) { "response header is not replayable" }
            canonicalHeaders[canonicalName] = copiedValues
        }
        require(aggregateBytes <= policy.maxAggregateHeaderBytes) {
            "response headers exceed aggregate replay limit"
        }

        return prepared.copy(
            responseBody = prepared.responseBody.copyOf(),
            responseHeaders = canonicalHeaders.toSortedMap().mapValues { (_, values) -> values.toList() },
        )
    }

    internal companion object {
        /** Test-fixture-only policy for adapter simulations that need problem responses. */
        fun syntheticForTests(policy: JobSubmissionIdempotencyPolicy): JobSubmissionSnapshotPolicy =
            JobSubmissionSnapshotPolicy(policy, SYNTHETIC_STATUSES, CONTENT_TYPES, SYNTHETIC_REPLAY_HEADER_ALLOWLIST)

        const val APPLICATION_JSON = "application/json"
        val CONTENT_TYPES = setOf(APPLICATION_JSON, "application/problem+json")
        val SYNTHETIC_STATUSES = setOf(201, 422)
        val HEADER_NAME = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")
        const val MAX_HEADER_NAME_LENGTH = 128
        val SENSITIVE_HEADER_PATTERN = Regex(".*(auth|credential|cookie|password|secret|token|api[-_]?key).*")
        val REPLAY_HEADER_ALLOWLIST: Set<String> = emptySet()
        val SYNTHETIC_REPLAY_HEADER_ALLOWLIST: Set<String> = setOf("etag")
        val FORBIDDEN_REPLAY_HEADERS =
            setOf(
                "authorization",
                "cookie",
                "set-cookie",
                "proxy-authenticate",
                "proxy-authorization",
                "connection",
                "keep-alive",
                "transfer-encoding",
                "upgrade",
                "content-length",
                "content-type",
                "idempotency-replayed",
                "retry-after",
                "x-api-key",
            )
    }
}
