package io.bluetape4k.workshop.operations.jobconsole.idempotency

import java.nio.charset.StandardCharsets.UTF_8
import java.util.*

/** Validates and canonicalizes the bounded HTTP response snapshot before persistence. */
/** Validates and canonicalizes the bounded HTTP response snapshot before persistence. */
internal class JobSubmissionSnapshotPolicy private constructor(
    /**
     * `policy`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val policy: JobSubmissionIdempotencyPolicy,
    /**
     * `acceptedStatuses`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    private val acceptedStatuses: Set<Int>,
    /**
     * `acceptedContentTypes`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val acceptedContentTypes: Set<String>,
    /**
     * `replayHeaderAllowlist`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val replayHeaderAllowlist: Set<String> = emptySet(),
) {
    internal constructor(policy: JobSubmissionIdempotencyPolicy):
            this(policy, setOf(202), setOf(APPLICATION_JSON))

    /**
     * validate은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param prepared 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
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
        /** Test-fixture-only policy for adapter simulations that need problem responses. */
        fun syntheticForTests(policy: JobSubmissionIdempotencyPolicy): JobSubmissionSnapshotPolicy =
            JobSubmissionSnapshotPolicy(policy, SYNTHETIC_STATUSES, CONTENT_TYPES, SYNTHETIC_REPLAY_HEADER_ALLOWLIST)

        /**
         * `APPLICATION_JSON`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        const val APPLICATION_JSON = "application/json"
        /**
         * `CONTENT_TYPES`은 작업 운영 콘솔에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
         *
         * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
         */
        val CONTENT_TYPES = setOf(APPLICATION_JSON, "application/problem+json")
        /**
         * `SYNTHETIC_STATUSES`은 작업 운영 콘솔에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
         *
         * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
         */
        val SYNTHETIC_STATUSES = setOf(201, 422)
        /**
         * `HEADER_NAME`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val HEADER_NAME = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")
        /**
         * `MAX_HEADER_NAME_LENGTH`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        const val MAX_HEADER_NAME_LENGTH = 128
        /**
         * `SENSITIVE_HEADER_PATTERN`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val SENSITIVE_HEADER_PATTERN = Regex(".*(auth|credential|cookie|password|secret|token|api[-_]?key).*")
        /**
         * `REPLAY_HEADER_ALLOWLIST`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val REPLAY_HEADER_ALLOWLIST: Set<String> = emptySet()
        /**
         * `SYNTHETIC_REPLAY_HEADER_ALLOWLIST`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val SYNTHETIC_REPLAY_HEADER_ALLOWLIST: Set<String> = setOf("etag")
        /**
         * `FORBIDDEN_REPLAY_HEADERS`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
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
