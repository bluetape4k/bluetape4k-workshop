package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.toBigInt
import io.bluetape4k.support.toUtf8Bytes
import java.math.BigInteger
import java.security.MessageDigest
import java.util.*

/**
 * TicketDeterministicSchedule 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal object TicketDeterministicSchedule {

/**
 * generate은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun generate(vector: TicketScheduleVector): List<TicketScheduleToken> {
        vector.name.requireNotBlank("vector.name")
        vector.seed.requireNotBlank("vector.seed")
        vector.profileSchemaVersion.requirePositiveNumber("profileSchemaVersion")
        vector.operationCount.requirePositiveNumber("operationCount")
        vector.durationNanos.requirePositiveNumber("durationNanos")
        vector.authorityWeights.requireNotEmpty("authorityWeights")
        vector.authorityWeights.forEach { it.requirePositiveNumber("authorityWeight") }

        return when (vector.curve) {
            TicketArrivalCurve.BURST -> burst(vector)
            TicketArrivalCurve.STEP -> step(vector)
            TicketArrivalCurve.RETRY_STORM -> retryStorm(vector)
        }
    }

/**
 * digest은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun digest(tokens: List<TicketScheduleToken>): String {
        val canonical = tokens.joinToString(separator = "\n", postfix = "\n") {
            "${it.offsetNanos}:${it.stableOrdinal}:${it.identityOrdinal}:${it.attemptOrdinal}:${it.authorityOrdinal}"
        }
        return sha256(canonical.toUtf8Bytes()).toHex()
    }

/**
 * burst은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun burst(vector: TicketScheduleVector): List<TicketScheduleToken> {
        require(vector.epochs.isEmpty() && vector.retryShape == null) {
            "burst schedule cannot declare epochs or retryShape"
        }
        return List(vector.operationCount) { ordinal ->
            token(vector, 0L, ordinal, ordinal, 0)
        }
    }

/**
 * step은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun step(vector: TicketScheduleVector): List<TicketScheduleToken> {
        require(vector.retryShape == null) { "step schedule cannot declare retryShape" }
        vector.epochs.requireNotEmpty("epochs")
        vector.epochs.sumOf { it.operationCount }.requireEquals(vector.operationCount, "step operation count")
        vector.epochs.sumOf { it.durationNanos }.requireEquals(vector.durationNanos, "step duration")

        var epochStart = 0L
        var stableOrdinal = 0
        return buildList(vector.operationCount) {
            vector.epochs.forEach { epoch ->
                epoch.durationNanos.requirePositiveNumber("epoch.durationNanos")
                epoch.operationCount.requirePositiveNumber("epoch.operationCount")
                repeat(epoch.operationCount) { epochOrdinal ->
                    add(
                        token(
                            vector = vector,
                            offsetNanos = Math.addExact(
                                epochStart,
                                scaledOffset(epochOrdinal, epoch.durationNanos, epoch.operationCount),
                            ),
                            stableOrdinal = stableOrdinal,
                            identityOrdinal = stableOrdinal,
                            attemptOrdinal = 0,
                        ),
                    )
                    stableOrdinal++
                }
                epochStart = Math.addExact(epochStart, epoch.durationNanos)
            }
        }.sortedWith(compareBy<TicketScheduleToken> { it.offsetNanos }.thenBy { it.stableOrdinal })
    }

/**
 * retryStorm은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun retryStorm(vector: TicketScheduleVector): List<TicketScheduleToken> {
        require(vector.epochs.isEmpty()) { "retry-storm schedule cannot declare epochs" }

        val retry = requireNotNull(vector.retryShape) { "retry-storm schedule requires retryShape" }
        retry.identityCount.requirePositiveNumber("retryShape.identityCount")
        retry.attemptsPerIdentity.requirePositiveNumber("retryShape.attemptsPerIdentity")
        Math.multiplyExact(retry.identityCount, retry.attemptsPerIdentity)
            .requireEquals(vector.operationCount, "retry operation count")

        val ranked = List(retry.identityCount) {
            RankedIdentity(it, rankDigest(vector, IDENTITY_KIND, it))
        }.sortedWith { left, right ->
            Arrays.compareUnsigned(left.digest, right.digest).takeIf { it != 0 }
                ?: left.ordinal.compareTo(right.ordinal)
        }

        val rankByIdentity = ranked.mapIndexed { rank, identity -> identity.ordinal to rank }.toMap()
        var stableOrdinal = 0

        return buildList(vector.operationCount) {
            ranked.forEach { identity ->
                repeat(retry.attemptsPerIdentity) { attemptOrdinal ->
                    add(
                        token(
                            vector,
                            scaledOffset(stableOrdinal, vector.durationNanos, vector.operationCount),
                            stableOrdinal,
                            identity.ordinal,
                            attemptOrdinal,
                        ),
                    )
                    stableOrdinal++
                }
            }
        }.sortedWith(
            compareBy<TicketScheduleToken> { it.offsetNanos }
                .thenBy { rankByIdentity.getValue(it.identityOrdinal) }
                .thenBy { it.attemptOrdinal }
                .thenBy { it.stableOrdinal },
        )
    }

/**
 * token은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun token(
        vector: TicketScheduleVector,
        offsetNanos: Long,
        stableOrdinal: Int,
        identityOrdinal: Int,
        attemptOrdinal: Int,
    ): TicketScheduleToken =
        TicketScheduleToken(
            offsetNanos,
            stableOrdinal,
            identityOrdinal,
            attemptOrdinal,
            selectAuthority(vector, identityOrdinal),
        )

/**
 * selectAuthority은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun selectAuthority(vector: TicketScheduleVector, identityOrdinal: Int): Int {
        val totalWeight = vector.authorityWeights.sumOf(Int::toLong)
        val position = BigInteger(
            1,
            rankDigest(vector, AUTHORITY_KIND, identityOrdinal).copyOfRange(0, Long.SIZE_BYTES)
        ).mod(totalWeight.toBigInt()).toLong()

        var upperExclusive = 0L
        vector.authorityWeights.forEachIndexed { ordinal, weight ->
            upperExclusive = Math.addExact(upperExclusive, weight.toLong())
            if (position < upperExclusive) {
                return ordinal
            }
        }
        error("authority selection escaped the validated weight range")
    }

/**
 * rankDigest은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun rankDigest(vector: TicketScheduleVector, kind: String, ordinal: Int): ByteArray =
        sha256("${vector.profileSchemaVersion}:${vector.seed}:$kind:$ordinal".toUtf8Bytes())

/**
 * scaledOffset은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun scaledOffset(ordinal: Int, durationNanos: Long, operationCount: Int): Long =
        BigInteger.valueOf(ordinal.toLong())
            .multiply(durationNanos.toBigInt())
            .divide(operationCount.toLong().toBigInt())
            .longValueExact()

/**
 * sha256은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

/**
 * toHex은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * RankedIdentity 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private data class RankedIdentity(
/**
 * ordinal은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val ordinal: Int,
/**
 * digest은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val digest: ByteArray,
    )

/**
 * IDENTITY_KIND은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val IDENTITY_KIND = "identity"
/**
 * AUTHORITY_KIND은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val AUTHORITY_KIND = "authority"
}
