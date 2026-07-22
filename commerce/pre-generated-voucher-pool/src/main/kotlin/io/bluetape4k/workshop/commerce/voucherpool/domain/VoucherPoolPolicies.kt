package io.bluetape4k.workshop.commerce.voucherpool.domain

import java.io.Serializable

/** Allowed campaign lifecycle transitions. */
object CampaignPolicy {
    private val transitions =
        setOf(
            CampaignState.DRAFT to CampaignState.ACTIVE,
            CampaignState.DRAFT to CampaignState.REVOKING,
            CampaignState.ACTIVE to CampaignState.PAUSED,
            CampaignState.ACTIVE to CampaignState.REVOKING,
            CampaignState.PAUSED to CampaignState.ACTIVE,
            CampaignState.PAUSED to CampaignState.REVOKING,
            CampaignState.REVOKING to CampaignState.REVOKED,
        )

    /** Returns whether [from] may transition directly to [to]. */
    fun canTransition(
        from: CampaignState,
        to: CampaignState,
    ): Boolean = from to to in transitions
}

/** Allowed batch checkpoint and terminal lifecycle transitions. */
object BatchPolicy {
    private val transitions =
        setOf(
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

    /** Returns whether [from] may transition directly to [to]. */
    fun canTransition(
        from: BatchState,
        to: BatchState,
    ): Boolean = from to to in transitions
}

/** Allowed entry transitions that preserve voucher non-reuse. */
object EntryPolicy {
    private val transitions =
        setOf(
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

    /** Returns whether [from] may transition directly to [to]. */
    fun canTransition(
        from: EntryState,
        to: EntryState,
    ): Boolean = from to to in transitions
}

/** Allowed reservation transitions; every target state is terminal. */
object ReservationPolicy {
    private val transitions =
        setOf(
            ReservationState.ACTIVE to ReservationState.ALLOCATED,
            ReservationState.ACTIVE to ReservationState.EXPIRED,
            ReservationState.ACTIVE to ReservationState.RELEASED,
            ReservationState.ACTIVE to ReservationState.REVOKED,
        )

    /** Returns whether [from] may transition directly to [to]. */
    fun canTransition(
        from: ReservationState,
        to: ReservationState,
    ): Boolean = from to to in transitions
}

/** Persistence action for the full safe response descriptor. */
enum class DescriptorAction {
    NONE,
    RELEASE,
    STORE,
    STORE_SAFE,
}

/** Persistence action for the tenant-lifetime command tombstone. */
enum class TombstoneAction {
    NONE,
    STORE,
    RETAIN,
}

/** Stable caller recovery guidance associated with an error response. */
enum class CallerRecovery {
    RETRY_AFTER,
    CHANGE_PAYLOAD_OR_KEY,
    LOOK_UP_EFFECT,
    BOUNDED_BACKOFF,
    TERMINAL_OR_OPERATOR_REVIEW,
    REFRESH_SNAPSHOT,
    REFRESH_STATE_WITH_BACKOFF,
    USE_NEW_SCOPE_OR_OPERATOR_REVIEW,
    CREATE_RESERVATION_OR_RECOVER,
    DO_NOT_EXPOSE_RESOURCE,
    ESCALATE_FAIL_CLOSED,
    USE_REPLACEMENT_FLOW,
}

/** HTTP, replay, persistence, and recovery semantics for one stable error code. */
data class VoucherPoolErrorSemantics(
    val httpStatus: Int,
    val retryable: Boolean,
    val descriptorAction: DescriptorAction,
    val tombstoneAction: TombstoneAction,
    val callerRecovery: CallerRecovery,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Complete stable error catalog shared by HTTP handlers and workers. */
object VoucherPoolErrorCatalog {
    private const val HTTP_OK = 200
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_CONFLICT = 409
    private const val HTTP_GONE = 410
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVICE_UNAVAILABLE = 503

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
                semantics =
                    semantics(
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

    /** The exact set of public error codes represented by this catalog. */
    val codes: Set<VoucherPoolErrorCode>
        get() = semanticsByCode.keys

    /** Returns the stable semantics for [code]. */
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
                semantics =
                    semantics(
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
                semantics =
                    semantics(
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
            semantics =
                semantics(
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
        check(put(code, semantics) == null) {
            "Duplicate voucher pool error semantics for $code."
        }
    }

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
