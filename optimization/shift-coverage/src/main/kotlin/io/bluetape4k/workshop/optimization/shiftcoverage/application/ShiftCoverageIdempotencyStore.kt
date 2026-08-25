package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.workshop.optimization.shiftcoverage.domain.IdempotencyKey
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** caller principal/scope를 포함한 idempotency namespace입니다. */
data class IdempotencyNamespace(
    val method: String,
    val route: String,
    val demoScope: String,
    val principal: String,
    val key: IdempotencyKey,
)

data class IdempotencyRecord(
    val fingerprintSha256: String,
    val response: String? = null,
)

enum class IdempotencyClaimKind { NEW, REPLAY, REUSED, IN_PROGRESS }

data class IdempotencyClaim(val kind: IdempotencyClaimKind, val response: String? = null)

/** process 재시작을 넘어 동일한 route/scope/key 계약을 유지하는 저장소 port입니다. */
interface ShiftCoverageIdempotencyPort {
    fun begin(namespace: IdempotencyNamespace, fingerprintSha256: String): IdempotencyClaim
    fun complete(namespace: IdempotencyNamespace, response: String): IdempotencyRecord
    fun abort(namespace: IdempotencyNamespace)
}

/** DB unique key와 동일한 semantics를 재현하는 restartable in-memory seam입니다. */
class ShiftCoverageIdempotencyStore(
    private val records: MutableMap<IdempotencyNamespace, IdempotencyRecord> = mutableMapOf(),
) : ShiftCoverageIdempotencyPort {
    private val lock = ReentrantLock()

    override fun begin(namespace: IdempotencyNamespace, fingerprintSha256: String): IdempotencyClaim = lock.withLock {
        validateFingerprint(fingerprintSha256)
        val current = records[namespace]
        if (current == null) {
            records[namespace] = IdempotencyRecord(fingerprintSha256)
            IdempotencyClaim(IdempotencyClaimKind.NEW)
        } else if (current.fingerprintSha256 != fingerprintSha256) {
            IdempotencyClaim(IdempotencyClaimKind.REUSED)
        } else if (current.response == null) {
            IdempotencyClaim(IdempotencyClaimKind.IN_PROGRESS)
        } else {
            IdempotencyClaim(IdempotencyClaimKind.REPLAY, current.response)
        }
    }

    override fun complete(namespace: IdempotencyNamespace, response: String): IdempotencyRecord = lock.withLock {
        val current = records[namespace] ?: error("idempotency claim does not exist")
        records[namespace] = current.copy(response = response)
        records.getValue(namespace)
    }

    /** 결과가 쓰였다는 증거가 없는 실패는 retryable claim으로 되돌립니다. */
    override fun abort(namespace: IdempotencyNamespace) {
        lock.withLock {
            records.remove(namespace)
        }
    }

    fun isWriteSuppressed(namespace: IdempotencyNamespace, fingerprintSha256: String): Boolean =
        beginWithoutMutation(namespace, fingerprintSha256).kind != IdempotencyClaimKind.NEW

    private fun beginWithoutMutation(namespace: IdempotencyNamespace, fingerprintSha256: String): IdempotencyClaim = lock.withLock {
        validateFingerprint(fingerprintSha256)
        val current = records[namespace] ?: return@withLock IdempotencyClaim(IdempotencyClaimKind.NEW)
        if (current.fingerprintSha256 == fingerprintSha256) {
            if (current.response == null) IdempotencyClaim(IdempotencyClaimKind.IN_PROGRESS)
            else IdempotencyClaim(IdempotencyClaimKind.REPLAY, current.response)
        } else IdempotencyClaim(IdempotencyClaimKind.REUSED)
    }

    private fun validateFingerprint(value: String) {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "fingerprint must be lowercase SHA-256" }
    }
}
