package io.bluetape4k.workshop.optimization.shiftcoverage.domain

/** 외부 입력과 immutable snapshot 경계가 위반되었을 때 사용하는 안정적인 오류입니다. */
class InvalidShiftCoverageInput(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** signed minor-unit arithmetic가 overflow 되었을 때 사용하는 안정적인 오류입니다. */
class ShiftCoverageArithmeticError(message: String, cause: Throwable? = null) : ArithmeticException(message)

/** mutation이 현재 revision 또는 scope와 충돌했을 때 사용하는 오류입니다. */
class ShiftCoverageConflict(val code: ShiftCoverageConflictCode, message: String = code.name) : RuntimeException(message)

enum class ShiftCoverageConflictCode {
    REVISION_CONFLICT,
    IDEMPOTENCY_KEY_REUSED,
    CALLBACK_REPLAY,
    EVENT_KEY_REUSED,
    STALE,
    REPLAN_REJECTED,
    STARTED_SHIFT,
}

/** planner가 후보/시간 상한을 넘겼을 때 사용하는 오류입니다. */
enum class PlannerFailureCode {
    PLANNER_LIMIT_EXCEEDED,
    REPLAN_TIMEOUT,
}

class ShiftCoveragePlannerFailure(val code: PlannerFailureCode, message: String = code.name) : RuntimeException(message)
