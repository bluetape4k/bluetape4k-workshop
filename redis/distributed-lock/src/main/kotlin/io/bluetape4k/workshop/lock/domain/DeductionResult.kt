package io.bluetape4k.workshop.lock.domain

import java.io.Serializable

/**
 * inventory deduction operation 을 위한 sealed result hierarchy 입니다.
 *
 * ## Variants
 * - [Success] — deduction 이 성공했습니다. [remaining] 은 deduction 이후 stock 입니다. [token] 은 fencing token 을 사용한 path 에서만 설정됩니다.
 * - [InsufficientStock] — stock 이 부족합니다. check 시점에 [requested] > [available] 입니다.
 * - [Rejected] — [token] 이 stale 이라서 fencing guard 가 write 를 거부했습니다.
 * - [LockNotAcquired] — wait timeout 안에 distributed lock 을 acquire 하지 못했습니다.
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
