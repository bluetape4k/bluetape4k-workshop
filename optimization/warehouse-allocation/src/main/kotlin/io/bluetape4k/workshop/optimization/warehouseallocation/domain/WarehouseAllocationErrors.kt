package io.bluetape4k.workshop.optimization.warehouseallocation.domain

internal enum class WarehouseAllocationErrorCode {
    INVALID_REQUEST,
    INVALID_REQUEST_ID,
    UNKNOWN_TARGET,
    RESERVATION_CONFLICT,
    ACTIVE_PLAN_CONFLICT,
    EVENT_KEY_REUSED,
    EVENT_REVISION_CONFLICT,
    STALE_EVENT,
    IDEMPOTENCY_FINGERPRINT_CONFLICT,
    COMMAND_IN_PROGRESS,
    PLANNER_INPUT_TOO_LARGE,
    PLANNER_DEADLINE_EXCEEDED,
    PLANNER_OUTPUT_TOO_LARGE,
    PLANNER_CAPACITY_EXCEEDED,
    RETRY_EXHAUSTED,
    OUTBOX_NOT_REDRIVABLE,
    RESPONSE_TOO_LARGE,
}

internal enum class WarehouseAllocationNextAction { POLL, REPLAN, SHRINK_DATASET, RETRY_AFTER, RECONCILE, NO_RETRY }

internal class WarehouseAllocationException(
    val code: WarehouseAllocationErrorCode,
    message: String,
    val retryable: Boolean = false,
    val nextAction: WarehouseAllocationNextAction = WarehouseAllocationNextAction.NO_RETRY,
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal fun WarehouseAllocationException.toErrorDto(requestId: String): WarehouseAllocationErrorDto =
    WarehouseAllocationErrorDto(code.name, requestId, retryable, retryAfterSeconds, nextAction.name)

internal data class WarehouseAllocationErrorDto(
    val code: String,
    val requestId: String,
    val retryable: Boolean,
    val retryAfterSeconds: Int? = null,
    val nextAction: String,
    val operationKey: String? = null,
)
