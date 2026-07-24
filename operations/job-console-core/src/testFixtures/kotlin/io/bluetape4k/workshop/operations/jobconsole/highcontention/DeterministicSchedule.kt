package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Arrays

object DeterministicSchedule {

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

    fun digest(tokens: List<ScheduleToken>): String {
        val canonical = tokens.joinToString(separator = "\n", postfix = "\n") {
            "${it.offsetNanos}:${it.stableOrdinal}:${it.identityOrdinal}:${it.attemptOrdinal}:${it.authorityOrdinal}"
        }
        return sha256(canonical.toByteArray(Charsets.UTF_8)).toHex()
    }

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

    private fun rankDigest(vector: ScheduleVector, kind: String, ordinal: Int): ByteArray =
        sha256("${vector.profileSchemaVersion}:${vector.seed}:$kind:$ordinal".toByteArray(Charsets.UTF_8))

    private fun scaledOffset(
        ordinal: Int,
        durationNanos: Long,
        operationCount: Int,
    ): Long =
        BigInteger.valueOf(ordinal.toLong())
            .multiply(BigInteger.valueOf(durationNanos))
            .divide(BigInteger.valueOf(operationCount.toLong()))
            .longValueExact()

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private data class RankedIdentity(
        val ordinal: Int,
        val digest: ByteArray,
    )

    private const val IDENTITY_KIND = "identity"
    private const val AUTHORITY_KIND = "authority"
}
