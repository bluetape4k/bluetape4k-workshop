package io.bluetape4k.workshop.optimization.fieldservice.domain

/** Field Service 입력이 경계 계약을 위반했을 때 사용하는 안정적인 예외입니다. */
class InvalidFieldServiceInput(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** 동시 변경으로 명령을 적용할 수 없을 때 사용하는 도메인 충돌입니다. */
class FieldServiceConflict(
    val code: FieldServiceConflictCode,
    message: String = code.name,
) : RuntimeException(message)

enum class FieldServiceConflictCode {
    EVENT_KEY_REUSED,
    VERSION_CONFLICT,
    SCHEDULE_CONFLICT,
    REPLAN_REJECTED,
    STALE_REVISION,
    STALE_REQUEST_GENERATION,
}
