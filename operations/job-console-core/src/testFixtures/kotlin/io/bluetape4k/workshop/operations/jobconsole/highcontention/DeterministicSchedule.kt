package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Arrays

/**
 * DeterministicSchedule 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
object DeterministicSchedule {

/**
 * generate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun generate(vector: ScheduleVector): List<ScheduleToken> {
        vector.name.requireNotBlank("vector.name")
        vector.seed.requireNotBlank("vector.seed")
        vector.profileSchemaVersion.requirePositiveNumber("profileSchemaVersion")
        vector.operationCount.requirePositiveNumber("operationCount")
        vector.durationNanos.requirePositiveNumber("durationNanos")
        vector.authorityWeights.requireNotEmpty("authorityWeights")
        vector.authorityWeights.forEach { it.requirePositiveNumber("authorityWeight") }

        return when (vector.curve) {
            ArrivalCurve.BURST -> burst(vector)
            ArrivalCurve.STEP -> step(vector)
            ArrivalCurve.RETRY_STORM -> retryStorm(vector)
        }
    }

/**
 * digest은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun digest(tokens: List<ScheduleToken>): String {
        val canonical = tokens.joinToString(separator = "\n", postfix = "\n") {
            "${it.offsetNanos}:${it.stableOrdinal}:${it.identityOrdinal}:${it.attemptOrdinal}:${it.authorityOrdinal}"
        }
        return sha256(canonical.toByteArray(Charsets.UTF_8)).toHex()
    }

/**
 * burst은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun burst(vector: ScheduleVector): List<ScheduleToken> {
        if (vector.epochs.isNotEmpty() || vector.retryShape != null) {
            throw IllegalArgumentException("burst schedule cannot declare epochs or retryShape")
        }
        return List(vector.operationCount) { ordinal ->
            token(
                vector = vector,
                offsetNanos = 0L,
                stableOrdinal = ordinal,
                identityOrdinal = ordinal,
                attemptOrdinal = 0,
            )
        }
    }

/**
 * step은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun step(vector: ScheduleVector): List<ScheduleToken> {
        if (vector.retryShape != null) {
            throw IllegalArgumentException("step schedule cannot declare retryShape")
        }
        vector.epochs.requireNotEmpty("epochs")
        vector.epochs.fold(0) { total, epoch ->
            epoch.durationNanos.requirePositiveNumber("epoch.durationNanos")
            epoch.operationCount.requirePositiveNumber("epoch.operationCount")
            Math.addExact(total, epoch.operationCount)
        }.requireEquals(vector.operationCount, "step operation count")
        vector.epochs.fold(0L) { total, epoch ->
            Math.addExact(total, epoch.durationNanos)
        }.requireEquals(vector.durationNanos, "step duration")

        val tokens = ArrayList<ScheduleToken>(vector.operationCount)
        var epochStart = 0L
        var stableOrdinal = 0
        vector.epochs.forEach { epoch ->
            repeat(epoch.operationCount) { epochOrdinal ->
                val offset = Math.addExact(
                    epochStart,
                    scaledOffset(epochOrdinal, epoch.durationNanos, epoch.operationCount),
                )
                tokens += token(vector, offset, stableOrdinal, stableOrdinal, attemptOrdinal = 0)
                stableOrdinal++
            }
            epochStart = Math.addExact(epochStart, epoch.durationNanos)
        }
        return tokens.sortedWith(
            compareBy<ScheduleToken> { it.offsetNanos }
                .thenBy { it.stableOrdinal },
        )
    }

/**
 * retryStorm은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun retryStorm(vector: ScheduleVector): List<ScheduleToken> {
        if (vector.epochs.isNotEmpty()) {
            throw IllegalArgumentException("retry-storm schedule cannot declare epochs")
        }
        val retry = vector.retryShape
            ?: throw IllegalArgumentException("retry-storm schedule requires retryShape")
        retry.identityCount.requirePositiveNumber("retryShape.identityCount")
        retry.attemptsPerIdentity.requirePositiveNumber("retryShape.attemptsPerIdentity")
        Math.multiplyExact(retry.identityCount, retry.attemptsPerIdentity)
            .requireEquals(vector.operationCount, "retry operation count")

        val rankedIdentities = (0 until retry.identityCount)
            .map { ordinal -> RankedIdentity(ordinal, rankDigest(vector, IDENTITY_KIND, ordinal)) }
            .sortedWith { left, right ->
                Arrays.compareUnsigned(left.digest, right.digest)
                    .takeIf { it != 0 }
                    ?: left.ordinal.compareTo(right.ordinal)
            }
        val identityRank = rankedIdentities
            .mapIndexed { rank, identity -> identity.ordinal to rank }
            .toMap()

        val tokens = ArrayList<ScheduleToken>(vector.operationCount)
        var stableOrdinal = 0
        rankedIdentities.forEach { identity ->
            repeat(retry.attemptsPerIdentity) { attemptOrdinal ->
                tokens += token(
                    vector = vector,
                    offsetNanos = scaledOffset(stableOrdinal, vector.durationNanos, vector.operationCount),
                    stableOrdinal = stableOrdinal,
                    identityOrdinal = identity.ordinal,
                    attemptOrdinal = attemptOrdinal,
                )
                stableOrdinal++
            }
        }
        return tokens.sortedWith(
            compareBy<ScheduleToken> { it.offsetNanos }
                .thenBy { identityRank.getValue(it.identityOrdinal) }
                .thenBy { it.attemptOrdinal }
                .thenBy { it.stableOrdinal },
        )
    }

/**
 * token은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun token(
        vector: ScheduleVector,
        offsetNanos: Long,
        stableOrdinal: Int,
        identityOrdinal: Int,
        attemptOrdinal: Int,
    ): ScheduleToken =
        ScheduleToken(
            offsetNanos = offsetNanos,
            stableOrdinal = stableOrdinal,
            identityOrdinal = identityOrdinal,
            attemptOrdinal = attemptOrdinal,
            authorityOrdinal = selectAuthority(vector, identityOrdinal),
        )

/**
 * selectAuthority은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun selectAuthority(vector: ScheduleVector, identityOrdinal: Int): Int {
        val totalWeight = vector.authorityWeights.fold(0L) { total, weight ->
            Math.addExact(total, weight.toLong())
        }
        val digest = rankDigest(vector, AUTHORITY_KIND, identityOrdinal)
        val position = BigInteger(1, digest.copyOfRange(0, Long.SIZE_BYTES))
            .mod(BigInteger.valueOf(totalWeight))
            .toLong()
        var upperExclusive = 0L
        vector.authorityWeights.forEachIndexed { authorityOrdinal, weight ->
            upperExclusive = Math.addExact(upperExclusive, weight.toLong())
            if (position < upperExclusive) {
                return authorityOrdinal
            }
        }
        error("authority selection position escaped the validated weight range")
    }

/**
 * rankDigest은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun rankDigest(vector: ScheduleVector, kind: String, ordinal: Int): ByteArray =
        sha256("${vector.profileSchemaVersion}:${vector.seed}:$kind:$ordinal".toByteArray(Charsets.UTF_8))

/**
 * scaledOffset은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun scaledOffset(
        ordinal: Int,
        durationNanos: Long,
        operationCount: Int,
    ): Long =
        BigInteger.valueOf(ordinal.toLong())
            .multiply(BigInteger.valueOf(durationNanos))
            .divide(BigInteger.valueOf(operationCount.toLong()))
            .longValueExact()

/**
 * sha256은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

/**
 * toHex은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

/**
 * RankedIdentity 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class RankedIdentity(
/**
 * ordinal은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val ordinal: Int,
/**
 * digest은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val digest: ByteArray,
    )

/**
 * IDENTITY_KIND은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val IDENTITY_KIND = "identity"
/**
 * AUTHORITY_KIND은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val AUTHORITY_KIND = "authority"
}
