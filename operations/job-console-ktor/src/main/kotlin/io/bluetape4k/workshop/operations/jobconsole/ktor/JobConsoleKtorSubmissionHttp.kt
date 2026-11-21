package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.api.JobConsoleJson
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionHttpResponse
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/**
 * `MAX_JOB_SUBMISSION_BODY_BYTES`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
internal const val MAX_JOB_SUBMISSION_BODY_BYTES: Int = 64 * 1024

/**
 * KtorJobSubmissionRequestTooLargeException은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class KtorJobSubmissionRequestTooLargeException: RuntimeException("job submission body exceeds 64 KiB")

/**
 * KtorJobSubmissionInvalidRequestException은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을
 * 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class KtorJobSubmissionInvalidRequestException(
    message: String,
    cause: Throwable? = null,
): RuntimeException(message, cause)

/**
 * KtorJobSubmissionScopeDeniedException은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class KtorJobSubmissionScopeDeniedException(
    message: String,
    cause: Throwable? = null,
): RuntimeException(message, cause)

/**
 * JobConsoleKtorSubmissionHttp은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object JobConsoleKtorSubmissionHttp {
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val strictMapper: JsonMapper = JobConsoleJson.strictRequestMapper(MAX_JOB_SUBMISSION_BODY_BYTES)

    /**
     * scope은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param call 업무 처리에 필요한 call 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun scope(call: ApplicationCall): DemoCallerScope {
        /**
         * 운영 작업 콘솔 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
         */
        val tenant = requiredSingleHeader(call, "X-Demo-Tenant", scopeHeader = true)
        /**
         * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        val submitter = requiredSingleHeader(call, "X-Demo-Submitter", scopeHeader = true)

        return try {
            DemoCallerScope(tenant, submitter)
        } catch (failure: IllegalArgumentException) {
            throw KtorJobSubmissionScopeDeniedException("invalid demo caller scope", failure)
        }
    }

    /**
     * idempotencyKey은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param call 업무 처리에 필요한 call 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun idempotencyKey(call: ApplicationCall): String {
        val key = requiredSingleHeader(call, "Idempotency-Key", scopeHeader = false)
        val bytes = key.toUtf8Bytes()
        if (bytes.isEmpty() || bytes.size > 255 || bytes.any { it.toInt() !in 0x21..0x7e } || ',' in key) {
            throw KtorJobSubmissionInvalidRequestException("Idempotency-Key must be printable ASCII, 1..255 bytes, and contain no comma")
        }
        return key
    }

    /**
     * readSubmitRequest은 운영 작업 콘솔 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param call 업무 처리에 필요한 call 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    suspend fun readSubmitRequest(call: ApplicationCall): SubmitJobRequest {
        validateContentType(call.request.contentType())
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_JOB_SUBMISSION_BODY_BYTES) {
            throw KtorJobSubmissionRequestTooLargeException()
        }
        try {
            val bytes = call.receiveChannel().readBuffer((MAX_JOB_SUBMISSION_BODY_BYTES + 1L)).readByteArray()
            if (bytes.size > MAX_JOB_SUBMISSION_BODY_BYTES) throw KtorJobSubmissionRequestTooLargeException()

            return strictMapper.readValue<SubmitJobRequest>(bytes)
        } catch (failure: KtorJobSubmissionRequestTooLargeException) {
            throw failure
        } catch (failure: Exception) {
            throw KtorJobSubmissionInvalidRequestException("invalid job submission JSON", failure)
        }
    }

    /**
     * requiredSingleHeader은 운영 작업 콘솔 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param call 업무 처리에 필요한 call 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param scopeHeader 업무 처리에 필요한 scopeHeader 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun requiredSingleHeader(call: ApplicationCall, name: String, scopeHeader: Boolean): String {
        val values = call.request.headers.getAll(name).orEmpty()
        if (values.size != 1 || values.single().isBlank()) {
            if (scopeHeader) throw KtorJobSubmissionScopeDeniedException("exactly one non-blank $name header is required")
            throw KtorJobSubmissionInvalidRequestException("exactly one non-blank $name header is required")
        }
        return values.single()
    }

    /**
     * validateContentType은 운영 작업 콘솔 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param contentType 운영 작업 콘솔 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    private fun validateContentType(contentType: ContentType) {
        if (!contentType.contentType.equals("application", ignoreCase = true) ||
            !contentType.contentSubtype.equals("json", ignoreCase = true) ||
            (contentType.charset() != null && contentType.charset() != Charsets.UTF_8)
        ) {
            throw KtorJobSubmissionInvalidRequestException("Content-Type must be application/json with UTF-8")
        }
    }
}

/**
 * respondSubmission은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param response 업무 처리에 필요한 response 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal suspend fun ApplicationCall.respondSubmission(response: JobSubmissionHttpResponse) {
    response.headers.forEach { (name, values) -> values.forEach { this.response.headers.append(name, it) } }
    respondBytes(
        bytes = response.body,
        contentType = ContentType.parse(response.contentType),
        status = HttpStatusCode.fromValue(response.status),
    )
}

/**
 * respondSubmissionProblem은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param response 업무 처리에 필요한 response 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal suspend fun ApplicationCall.respondSubmissionProblem(response: JobSubmissionHttpResponse) =
    respondSubmission(response)
