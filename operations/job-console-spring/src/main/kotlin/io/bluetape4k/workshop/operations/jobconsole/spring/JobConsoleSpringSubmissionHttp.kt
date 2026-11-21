package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.workshop.operations.jobconsole.api.JobConsoleJson
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.*

/**
 * `MAX_JOB_SUBMISSION_BODY_BYTES`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
 *
 * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
 */
internal const val MAX_JOB_SUBMISSION_BODY_BYTES: Int = 64 * 1024

/**
 * JobSubmissionRequestTooLargeException은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class JobSubmissionRequestTooLargeException: RuntimeException("job submission body exceeds 64 KiB")

/**
 * JobSubmissionInvalidRequestException은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을
 * 이
 * 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class JobSubmissionInvalidRequestException(
    message: String,
    cause: Throwable? = null,
): RuntimeException(message, cause)

/**
 * JobSubmissionScopeDeniedException은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class JobSubmissionScopeDeniedException(
    message: String,
    cause: Throwable? = null,
): RuntimeException(message, cause)

/**
 * JobConsoleSpringSubmissionHttp은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object JobConsoleSpringSubmissionHttp {
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val strictMapper: JsonMapper = JobConsoleJson.strictRequestMapper(MAX_JOB_SUBMISSION_BODY_BYTES)

    /**
     * scope은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 운영 작업 콘솔 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun scope(request: HttpServletRequest): DemoCallerScope {
        /**
         * 운영 작업 콘솔 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
         */
        val tenant = runCatching {
            request.singleHeader("X-Demo-Tenant")
        }.getOrElse {
            throw JobSubmissionScopeDeniedException("X-Demo-Tenant is required", it)
        }
        val submitter = runCatching {
            request.singleHeader("X-Demo-Submitter")
        }.getOrElse {
            throw JobSubmissionScopeDeniedException("X-Demo-Submitter is required", it)
        }
        return try {
            DemoCallerScope(tenant, submitter)
        } catch (failure: IllegalArgumentException) {
            throw JobSubmissionScopeDeniedException("invalid demo caller scope", failure)
        }
    }

    /**
     * idempotencyKey은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 운영 작업 콘솔 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun idempotencyKey(request: HttpServletRequest): String {
        val key = request.singleHeader("Idempotency-Key")
        val bytes = key.toByteArray(UTF_8)

        if (bytes.isEmpty() || bytes.size > 255 || bytes.any { it.toInt() !in 0x21..0x7e } || ',' in key) {
            throw JobSubmissionInvalidRequestException("Idempotency-Key must be printable ASCII, 1..255 bytes, and contain no comma")
        }
        return key
    }

    /**
     * readSubmitRequest은 운영 작업 콘솔 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 운영 작업 콘솔 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
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
            return strictMapper.readValue<SubmitJobRequest>(body.toByteArray())
        } catch (failure: JobSubmissionRequestTooLargeException) {
            throw failure
        } catch (failure: Exception) {
            throw JobSubmissionInvalidRequestException("invalid job submission JSON", failure)
        }
    }

    /**
     * singleHeader은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param name 업무 처리에 필요한 name 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun HttpServletRequest.singleHeader(name: String): String {
        val values = Collections.list(getHeaders(name))
        if (values.size != 1 || values.single().isBlank()) {
            throw JobSubmissionInvalidRequestException("exactly one non-blank $name header is required")
        }
        return values.single()
    }

    /**
     * validateContentType은 운영 작업 콘솔 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param raw 업무 처리에 필요한 raw 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validateContentType(raw: String?) {
        if (raw.isNullOrBlank()) throw JobSubmissionInvalidRequestException("Content-Type must be application/json")

        val mediaType = try {
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
