package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Arrays

internal object TicketDeterministicSchedule {
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

    fun digest(tokens: List<TicketScheduleToken>): String {
        val canonical = tokens.joinToString(separator = "\n", postfix = "\n") {
            "${it.offsetNanos}:${it.stableOrdinal}:${it.identityOrdinal}:${it.attemptOrdinal}:${it.authorityOrdinal}"
        }
        return sha256(canonical.toByteArray()).toHex()
    }

    private fun burst(vector: TicketScheduleVector): List<TicketScheduleToken> {
        require(vector.epochs.isEmpty() && vector.retryShape == null) {
            "burst schedule cannot declare epochs or retryShape"
        }
        return List(vector.operationCount) { ordinal ->
            token(vector, 0L, ordinal, ordinal, 0)
        }
    }

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

    private fun retryStorm(vector: TicketScheduleVector): List<TicketScheduleToken> {
        require(vector.epochs.isEmpty()) { "retry-storm schedule cannot declare epochs" }
        val retry = requireNotNull(vector.retryShape) { "retry-storm schedule requires retryShape" }
        retry.identityCount.requirePositiveNumber("retryShape.identityCount")
        retry.attemptsPerIdentity.requirePositiveNumber("retryShape.attemptsPerIdentity")
        Math.multiplyExact(retry.identityCount, retry.attemptsPerIdentity)
            .requireEquals(vector.operationCount, "retry operation count")

        val ranked = (0 until retry.identityCount)
            .map { RankedIdentity(it, rankDigest(vector, IDENTITY_KIND, it)) }
            .sortedWith { left, right ->
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

    private fun selectAuthority(vector: TicketScheduleVector, identityOrdinal: Int): Int {
        val totalWeight = vector.authorityWeights.sumOf(Int::toLong)
        val position = BigInteger(1, rankDigest(vector, AUTHORITY_KIND, identityOrdinal).copyOfRange(0, Long.SIZE_BYTES))
            .mod(BigInteger.valueOf(totalWeight))
            .toLong()
        var upperExclusive = 0L
        vector.authorityWeights.forEachIndexed { ordinal, weight ->
            upperExclusive = Math.addExact(upperExclusive, weight.toLong())
            if (position < upperExclusive) {
                return ordinal
            }
        }
        error("authority selection escaped the validated weight range")
    }

    private fun rankDigest(vector: TicketScheduleVector, kind: String, ordinal: Int): ByteArray =
        sha256("${vector.profileSchemaVersion}:${vector.seed}:$kind:$ordinal".toByteArray())

    private fun scaledOffset(ordinal: Int, durationNanos: Long, operationCount: Int): Long =
        BigInteger.valueOf(ordinal.toLong())
            .multiply(BigInteger.valueOf(durationNanos))
            .divide(BigInteger.valueOf(operationCount.toLong()))
            .longValueExact()

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class RankedIdentity(val ordinal: Int, val digest: ByteArray)

    private const val IDENTITY_KIND = "identity"
    private const val AUTHORITY_KIND = "authority"
}
