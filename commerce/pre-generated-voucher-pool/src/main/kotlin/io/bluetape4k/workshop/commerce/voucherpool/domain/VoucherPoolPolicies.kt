package io.bluetape4k.workshop.commerce.voucherpool.domain

import io.bluetape4k.support.checkNull
import java.io.Serializable

/** 허용된 campaign lifecycle transition입니다. */
/** 허용된 campaign lifecycle transition입니다. */
object CampaignPolicy {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val transitions = setOf(
        CampaignState.DRAFT to CampaignState.ACTIVE,
        CampaignState.DRAFT to CampaignState.REVOKING,
        CampaignState.ACTIVE to CampaignState.PAUSED,
        CampaignState.ACTIVE to CampaignState.REVOKING,
        CampaignState.PAUSED to CampaignState.ACTIVE,
        CampaignState.PAUSED to CampaignState.REVOKING,
        CampaignState.REVOKING to CampaignState.REVOKED,
    )

    /** [from]이 [to]로 직접 transition할 수 있는지 반환합니다. */
    /** [from]이 [to]로 직접 transition할 수 있는지 반환합니다. */
    fun canTransition(
        from: CampaignState,
        to: CampaignState,
    ): Boolean =
        (from to to) in transitions
}

/** 허용된 batch checkpoint 및 terminal lifecycle transition입니다. */
/** 허용된 batch checkpoint 및 terminal lifecycle transition입니다. */
object BatchPolicy {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val transitions = setOf(
        BatchState.STAGING to BatchState.ACTIVE,
        BatchState.STAGING to BatchState.FAILED_RETRYABLE,
        BatchState.STAGING to BatchState.FAILED_TERMINAL,
        BatchState.FAILED_RETRYABLE to BatchState.STAGING,
        BatchState.FAILED_RETRYABLE to BatchState.REVOKING,
        BatchState.FAILED_TERMINAL to BatchState.REVOKING,
        BatchState.ACTIVE to BatchState.PAUSED,
        BatchState.ACTIVE to BatchState.EXPIRING,
        BatchState.ACTIVE to BatchState.REVOKING,
        BatchState.PAUSED to BatchState.ACTIVE,
        BatchState.PAUSED to BatchState.EXPIRING,
        BatchState.PAUSED to BatchState.REVOKING,
        BatchState.EXPIRING to BatchState.EXPIRED,
        BatchState.REVOKING to BatchState.REVOKED,
    )

    /** [from]이 [to]로 직접 transition할 수 있는지 반환합니다. */
    /** [from]이 [to]로 직접 transition할 수 있는지 반환합니다. */
    fun canTransition(
        from: BatchState,
        to: BatchState,
    ): Boolean =
        (from to to) in transitions
}

/** voucher non-reuse를 보존하는 허용된 entry transition입니다. */
/** voucher non-reuse를 보존하는 허용된 entry transition입니다. */
object EntryPolicy {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val transitions = setOf(
        EntryState.AVAILABLE to EntryState.RESERVED,
        EntryState.AVAILABLE to EntryState.REVOKED,
        EntryState.AVAILABLE to EntryState.EXPIRED,
        EntryState.RESERVED to EntryState.AVAILABLE,
        EntryState.RESERVED to EntryState.ALLOCATED,
        EntryState.RESERVED to EntryState.EXPIRED,
        EntryState.RESERVED to EntryState.REVOKED,
        EntryState.ALLOCATED to EntryState.REDEEMED,
        EntryState.ALLOCATED to EntryState.RELEASED,
        EntryState.ALLOCATED to EntryState.EXPIRED,
        EntryState.ALLOCATED to EntryState.REVOKED,
    )

    /** [from]이 [to]로 직접 transition할 수 있는지 반환합니다. */
    /** [from]이 [to]로 직접 transition할 수 있는지 반환합니다. */
    fun canTransition(
        from: EntryState,
        to: EntryState,
    ): Boolean =
        (from to to) in transitions
}

/** 허용된 reservation transition입니다. 모든 target state는 terminal입니다. */
/** 허용된 reservation transition입니다. 모든 target state는 terminal입니다. */
object ReservationPolicy {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val transitions = setOf(
        ReservationState.ACTIVE to ReservationState.ALLOCATED,
        ReservationState.ACTIVE to ReservationState.EXPIRED,
        ReservationState.ACTIVE to ReservationState.RELEASED,
        ReservationState.ACTIVE to ReservationState.REVOKED,
    )

    /** [from]이 [to]로 직접 transition할 수 있는지 반환합니다. */
    /** [from]이 [to]로 직접 transition할 수 있는지 반환합니다. */
    fun canTransition(
        from: ReservationState,
        to: ReservationState,
    ): Boolean =
        (from to to) in transitions
}

/** full safe response descriptor를 위한 persistence action입니다. */
/** full safe response descriptor를 위한 persistence action입니다. */
enum class DescriptorAction {
    /**
     * `DescriptorAction.NONE`은 추가 정책이나 실패 조건이 적용되지 않는 기본값입니다.
     */
    NONE,
    /**
     * `DescriptorAction.RELEASE`은 예약·점유 자원을 반환하는 명령 또는 후속 조치입니다.
     */
    RELEASE,
    /**
     * `DescriptorAction.STORE`은 결과를 durable 저장소에 기록하는 처리 경로입니다.
     */
    STORE,
    /**
     * `DescriptorAction.STORE_SAFE`은 민감정보를 제거한 형태로만 저장 가능한 값입니다.
     */
    STORE_SAFE,
}

/** tenant-lifetime command tombstone을 위한 persistence action입니다. */
/** tenant-lifetime command tombstone을 위한 persistence action입니다. */
enum class TombstoneAction {
    /**
     * `TombstoneAction.NONE`은 추가 정책이나 실패 조건이 적용되지 않는 기본값입니다.
     */
    NONE,
    /**
     * `TombstoneAction.STORE`은 결과를 durable 저장소에 기록하는 처리 경로입니다.
     */
    STORE,
    /**
     * `TombstoneAction.RETAIN`은 정책상 보존 기간 동안 삭제하지 않고 유지하는 값입니다.
     */
    RETAIN,
}

/** error response와 연결된 안정적인 caller recovery guidance입니다. */
/** error response와 연결된 안정적인 caller recovery guidance입니다. */
enum class CallerRecovery {
    /**
     * `CallerRecovery.RETRY_AFTER`은 호출자가 다음 시도까지 기다려야 하는 지연 신호입니다.
     */
    RETRY_AFTER,
    /**
     * `CallerRecovery.CHANGE_PAYLOAD_OR_KEY`은 동일 idempotency key와 다른 payload가 충돌했으므로 요청을 수정해야 하는
     * 결과입니다.
     */
    CHANGE_PAYLOAD_OR_KEY,
    /**
     * `CallerRecovery.LOOK_UP_EFFECT`은 외부 effect의 durable receipt를 조회해 재실행 여부를 판단하는 조치입니다.
     */
    LOOK_UP_EFFECT,
    /**
     * `CallerRecovery.BOUNDED_BACKOFF`은 재시도 폭주를 막기 위해 상한이 있는 지수 backoff를 적용하는 조치입니다.
     */
    BOUNDED_BACKOFF,
    /**
     * `CallerRecovery.TERMINAL_OR_OPERATOR_REVIEW`은 자동 재시도를 멈추고 terminal 처리 또는 운영자 검토로 넘기는 조치입니다.
     */
    TERMINAL_OR_OPERATOR_REVIEW,
    /**
     * `CallerRecovery.REFRESH_SNAPSHOT`은 이벤트를 현재 상태로 간주하지 말고 authoritative snapshot을 다시 읽는 조치입니다.
     */
    REFRESH_SNAPSHOT,
    /**
     * `CallerRecovery.REFRESH_STATE_WITH_BACKOFF`은 최신 상태를 다시 읽되 provider·DB 부하를 막기 위해 backoff를
     * 적용하는 조치입니다.
     */
    REFRESH_STATE_WITH_BACKOFF,
    /**
     * `CallerRecovery.USE_NEW_SCOPE_OR_OPERATOR_REVIEW`은 scope를 바꾸거나 운영자 검토를 받아 충돌을 해소해야 하는 조치입니다.
     */
    USE_NEW_SCOPE_OR_OPERATOR_REVIEW,
    /**
     * `CallerRecovery.CREATE_RESERVATION_OR_RECOVER`은 새 reservation을 만들거나 기존 reservation 복구 경로를
     * 선택하는 조치입니다.
     */
    CREATE_RESERVATION_OR_RECOVER,
    /**
     * `CallerRecovery.DO_NOT_EXPOSE_RESOURCE`은 내부 자원 존재 여부를 외부에 노출하지 않고 동일한 안전 응답을 반환하는 조치입니다.
     */
    DO_NOT_EXPOSE_RESOURCE,
    /**
     * `CallerRecovery.ESCALATE_FAIL_CLOSED`은 자동 허용하지 말고 fail-closed로 차단한 뒤 운영자에게 escalation하는
     * 조치입니다.
     */
    ESCALATE_FAIL_CLOSED,
    /**
     * `CallerRecovery.USE_REPLACEMENT_FLOW`은 분실·오염된 원본을 재사용하지 않고 대체 발급 흐름을 사용하는 조치입니다.
     */
    USE_REPLACEMENT_FLOW,
}

/**
 * 안정적인 error code 하나에 대한 HTTP, replay, persistence, recovery semantic입니다.
 * @property httpStatus 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property retryable 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property descriptorAction 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property tombstoneAction 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property callerRecovery 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class VoucherPoolErrorSemantics(
    /**
     * `httpStatus`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val httpStatus: Int,
    /**
     * `retryable`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryable: Boolean,
    /**
     * `descriptorAction`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val descriptorAction: DescriptorAction,
    /**
     * `tombstoneAction`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val tombstoneAction: TombstoneAction,
    /**
     * `callerRecovery`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val callerRecovery: CallerRecovery,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 바우처·프로모션에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/** HTTP handler와 worker가 공유하는 완전하고 안정적인 error catalog입니다. */
/** HTTP handler와 worker가 공유하는 완전하고 안정적인 error catalog입니다. */
object VoucherPoolErrorCatalog {
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private const val HTTP_OK = 200
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private const val HTTP_NOT_FOUND = 404
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private const val HTTP_CONFLICT = 409
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private const val HTTP_GONE = 410
    /**
     * 프로모션 바우처 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
     */
    private const val HTTP_TOO_MANY_REQUESTS = 429
    /**
     * 프로모션 바우처 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private const val HTTP_SERVICE_UNAVAILABLE = 503
    /**
     * 프로모션 바우처의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    private val semanticsByCode: Map<VoucherPoolErrorCode, VoucherPoolErrorSemantics> =
        buildMap {
            register(
                code = VoucherPoolErrorCode.COMMAND_IN_PROGRESS,
                semantics =
                    semantics(
                        status = HTTP_CONFLICT,
                        retryable = true,
                        descriptor = DescriptorAction.RELEASE,
                        recovery = CallerRecovery.RETRY_AFTER,
                    ),
            )
            registerRetainedTombstone(
                code = VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT,
                status = HTTP_CONFLICT,
                recovery = CallerRecovery.CHANGE_PAYLOAD_OR_KEY,
            )
            registerRetainedTombstone(
                code = VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED,
                status = HTTP_GONE,
                recovery = CallerRecovery.LOOK_UP_EFFECT,
            )
            registerRetryable(
                status = HTTP_SERVICE_UNAVAILABLE,
                recovery = CallerRecovery.BOUNDED_BACKOFF,
                VoucherPoolErrorCode.POOL_BUSY,
                VoucherPoolErrorCode.BACKEND_TIMEOUT,
                VoucherPoolErrorCode.BATCH_FAILED_RETRYABLE,
            )
            registerTerminal(
                recovery = CallerRecovery.TERMINAL_OR_OPERATOR_REVIEW,
                VoucherPoolErrorCode.POOL_EXHAUSTED,
                VoucherPoolErrorCode.USER_LIMIT_REACHED,
            )
            register(
                code = VoucherPoolErrorCode.STALE_REVISION,
                semantics =
                    semantics(
                        status = HTTP_CONFLICT,
                        descriptor = DescriptorAction.RELEASE,
                        recovery = CallerRecovery.REFRESH_SNAPSHOT,
                    ),
            )
            registerRetryable(
                status = HTTP_CONFLICT,
                recovery = CallerRecovery.REFRESH_STATE_WITH_BACKOFF,
                VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE,
                VoucherPoolErrorCode.CAMPAIGN_PAUSED,
                VoucherPoolErrorCode.BATCH_PAUSED,
                VoucherPoolErrorCode.BATCH_EXPIRING,
            )
            registerTerminal(
                recovery = CallerRecovery.USE_NEW_SCOPE_OR_OPERATOR_REVIEW,
                VoucherPoolErrorCode.CAMPAIGN_REVOKING,
                VoucherPoolErrorCode.CAMPAIGN_REVOKED,
                VoucherPoolErrorCode.BATCH_REVOKED,
                VoucherPoolErrorCode.BATCH_EXPIRED,
                VoucherPoolErrorCode.BATCH_FAILED_TERMINAL,
            )
            registerTerminal(
                recovery = CallerRecovery.CREATE_RESERVATION_OR_RECOVER,
                VoucherPoolErrorCode.RESERVATION_EXPIRED,
                VoucherPoolErrorCode.ALLOCATION_EXPIRED,
            )
            register(
                code = VoucherPoolErrorCode.WRONG_OWNER,
                semantics =
                    semantics(
                        status = HTTP_NOT_FOUND,
                        descriptor = DescriptorAction.RELEASE,
                        recovery = CallerRecovery.DO_NOT_EXPOSE_RESOURCE,
                    ),
            )
            register(
                code = VoucherPoolErrorCode.SCOPE_NOT_FOUND,
                semantics =
                    semantics(
                        status = HTTP_NOT_FOUND,
                        descriptor = DescriptorAction.RELEASE,
                        recovery = CallerRecovery.DO_NOT_EXPOSE_RESOURCE,
                    ),
            )
            register(
                code = VoucherPoolErrorCode.RATE_LIMITED,
                semantics =
                    semantics(
                        status = HTTP_TOO_MANY_REQUESTS,
                        retryable = true,
                        descriptor = DescriptorAction.RELEASE,
                        recovery = CallerRecovery.RETRY_AFTER,
                    ),
            )
            registerRetryable(
                status = HTTP_SERVICE_UNAVAILABLE,
                recovery = CallerRecovery.ESCALATE_FAIL_CLOSED,
                VoucherPoolErrorCode.KEY_MATERIAL_UNAVAILABLE,
                VoucherPoolErrorCode.CIPHERTEXT_INVALID,
            )
            register(
                code = VoucherPoolErrorCode.ALREADY_REVEALED,
                semantics = semantics(
                    status = HTTP_OK,
                    descriptor = DescriptorAction.STORE_SAFE,
                    recovery = CallerRecovery.USE_REPLACEMENT_FLOW,
                ),
            )
        }.also { catalog ->
            check(catalog.keys == VoucherPoolErrorCode.entries.toSet()) {
                "Voucher pool error catalog must cover every error code."
            }
        }

    /** 이 catalog가 나타내는 public error code의 정확한 집합입니다. */
    /** 이 catalog가 나타내는 public error code의 정확한 집합입니다. */
    val codes: Set<VoucherPoolErrorCode>
        get() = semanticsByCode.keys

    /** [code]의 안정적인 semantic을 반환합니다. */
    /** [code]의 안정적인 semantic을 반환합니다. */
    operator fun get(code: VoucherPoolErrorCode): VoucherPoolErrorSemantics =
        semanticsByCode.getValue(code)

    private fun MutableMap<VoucherPoolErrorCode, VoucherPoolErrorSemantics>.registerRetryable(
        status: Int,
        recovery: CallerRecovery,
        vararg codes: VoucherPoolErrorCode,
    ) {
        codes.forEach { code ->
            register(
                code = code,
                semantics = semantics(
                    status = status,
                    retryable = true,
                    descriptor = DescriptorAction.RELEASE,
                    recovery = recovery,
                ),
            )
        }
    }

    private fun MutableMap<VoucherPoolErrorCode, VoucherPoolErrorSemantics>.registerTerminal(
        recovery: CallerRecovery,
        vararg codes: VoucherPoolErrorCode,
    ) {
        codes.forEach { code ->
            register(
                code = code,
                semantics = semantics(
                    status = HTTP_CONFLICT,
                    descriptor = DescriptorAction.STORE,
                    tombstone = TombstoneAction.STORE,
                    recovery = recovery,
                ),
            )
        }
    }

    private fun MutableMap<VoucherPoolErrorCode, VoucherPoolErrorSemantics>.registerRetainedTombstone(
        code: VoucherPoolErrorCode,
        status: Int,
        recovery: CallerRecovery,
    ) {
        register(
            code = code,
            semantics = semantics(
                status = status,
                descriptor = DescriptorAction.NONE,
                tombstone = TombstoneAction.RETAIN,
                recovery = recovery,
            ),
        )
    }

    private fun MutableMap<VoucherPoolErrorCode, VoucherPoolErrorSemantics>.register(
        code: VoucherPoolErrorCode,
        semantics: VoucherPoolErrorSemantics,
    ) {
        put(code, semantics).checkNull { "Duplicate voucher pool error semantics for $code." }
    }

    /**
     * semantics은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param retryable 업무 처리에 필요한 retryable 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param descriptor 업무 처리에 필요한 descriptor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tombstone 업무 처리에 필요한 tombstone 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param recovery 업무 처리에 필요한 recovery 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun semantics(
        status: Int,
        retryable: Boolean = false,
        descriptor: DescriptorAction,
        tombstone: TombstoneAction = TombstoneAction.NONE,
        recovery: CallerRecovery,
    ): VoucherPoolErrorSemantics =
        VoucherPoolErrorSemantics(
            httpStatus = status,
            retryable = retryable,
            descriptorAction = descriptor,
            tombstoneAction = tombstone,
            callerRecovery = recovery,
        )
}
