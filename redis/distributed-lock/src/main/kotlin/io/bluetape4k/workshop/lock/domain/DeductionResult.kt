package io.bluetape4k.workshop.lock.domain

import java.io.Serializable

/**
 * Sealed result hierarchy for an inventory deduction operation.
 *
 * ## Variants
 * - [Success] — deduction succeeded; [remaining] is the stock after deduction.
 *   [token] is set only when a fencing token was used (fenced lock paths).
 * - [InsufficientStock] — not enough stock; [requested] > [available] at check time.
 * - [Rejected] — a fencing guard rejected the write because the [token] was stale.
 * - [LockNotAcquired] — the distributed lock could not be acquired within the wait timeout.
 */
sealed interface DeductionResult {

    data class Success(
        val remaining: Int,
        val token: Long? = null,
    ) : DeductionResult, Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class InsufficientStock(
        val requested: Int,
        val available: Int,
    ) : DeductionResult, Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class Rejected(
        val token: Long,
    ) : DeductionResult, Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class LockNotAcquired(
        val lockName: String,
    ) : DeductionResult, Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
