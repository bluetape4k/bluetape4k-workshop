package io.bluetape4k.workshop.operations.jobconsole.idempotency

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale

/**
 * Immutable, bounded policy shared by every idempotency coordinator instance.
 *
 * The fingerprint intentionally contains no secrets. It is used for readiness
 * parity checks so that instances cannot silently run different admission or
 * replay limits.
 */
internal data class JobSubmissionIdempotencyPolicy(
    val ownerLease: Duration = Duration.ofSeconds(30),
    val prepareDeadline: Duration = Duration.ofSeconds(10),
    val waiterTimeout: Duration = Duration.ofSeconds(2),
    val maxWaitersPerKey: Int = 2,
    val maxWaitersPerInstance: Int = 32,
    val datasourcePoolSize: Int = 8,
    val idempotencyDbConcurrency: Int = 4,
    val ownerPrepareConcurrency: Int = 8,
    val connectionAcquireTimeout: Duration = Duration.ofMillis(250),
    val statementTimeout: Duration = Duration.ofMillis(500),
    val pollInitialInterval: Duration = Duration.ofMillis(25),
    val pollMaxInterval: Duration = Duration.ofMillis(100),
    val janitorBatchSize: Int = 100,
    val retention: Duration = Duration.ofHours(1),
    val maxKeyBytes: Int = 255,
    val maxBodyBytes: Int = 64 * 1024,
    val maxReplayBytes: Int = 64 * 1024,
    val maxHeaderNames: Int = 8,
    val maxHeaderValues: Int = 4,
    val maxHeaderValueBytes: Int = 4 * 1024,
    val maxAggregateHeaderBytes: Int = 16 * 1024,
) {

    init {
        require(ownerLease.hasPositiveDuration()) { "ownerLease must be positive" }
        require(prepareDeadline.hasPositiveDuration()) { "prepareDeadline must be positive" }
        require(waiterTimeout.hasPositiveDuration()) { "waiterTimeout must be positive" }
        require(ownerLease > prepareDeadline) { "ownerLease must exceed prepareDeadline" }
        require(maxWaitersPerKey > 0) { "maxWaitersPerKey must be positive" }
        require(maxWaitersPerInstance > 0) { "maxWaitersPerInstance must be positive" }
        require(maxWaitersPerKey <= maxWaitersPerInstance) {
            "maxWaitersPerKey must not exceed maxWaitersPerInstance"
        }
        require(datasourcePoolSize > 0) { "datasourcePoolSize must be positive" }
        require(idempotencyDbConcurrency in 1..datasourcePoolSize) {
            "idempotencyDbConcurrency must be within datasourcePoolSize"
        }
        require(ownerPrepareConcurrency > 0) { "ownerPrepareConcurrency must be positive" }
        require(connectionAcquireTimeout.hasPositiveDuration()) { "connectionAcquireTimeout must be positive" }
        require(statementTimeout.hasPositiveDuration()) { "statementTimeout must be positive" }
        require(statementTimeout.toMillis() > 0L) { "statementTimeout must be at least one millisecond" }
        require(pollInitialInterval.hasPositiveDuration()) { "pollInitialInterval must be positive" }
        require(pollMaxInterval.hasPositiveDuration()) { "pollMaxInterval must be positive" }
        require(pollInitialInterval <= pollMaxInterval) {
            "pollInitialInterval must not exceed pollMaxInterval"
        }
        require(janitorBatchSize > 0) { "janitorBatchSize must be positive" }
        require(retention.hasPositiveDuration()) { "retention must be positive" }
        require(maxKeyBytes in 1..MAX_KEY_BYTES) { "maxKeyBytes must be between 1 and $MAX_KEY_BYTES" }
        require(maxBodyBytes in 1..MAX_BODY_BYTES) { "maxBodyBytes must be between 1 and $MAX_BODY_BYTES" }
        require(maxReplayBytes in 1..maxBodyBytes) {
            "maxReplayBytes must be within maxBodyBytes"
        }
        require(maxHeaderNames in 1..MAX_HEADER_NAMES) {
            "maxHeaderNames must be between 1 and $MAX_HEADER_NAMES"
        }
        require(maxHeaderValues in 1..MAX_HEADER_VALUES) {
            "maxHeaderValues must be between 1 and $MAX_HEADER_VALUES"
        }
        require(maxHeaderValueBytes in 1..MAX_HEADER_VALUE_BYTES) {
            "maxHeaderValueBytes must be between 1 and $MAX_HEADER_VALUE_BYTES"
        }
        require(maxAggregateHeaderBytes in 1..MAX_AGGREGATE_HEADER_BYTES) {
            "maxAggregateHeaderBytes must be between 1 and $MAX_AGGREGATE_HEADER_BYTES"
        }
    }

    /** Stable readiness value; never include raw keys, scopes, or owner tokens. */
    val fingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        digest(
            domain = "job-console-idempotency-policy-v1",
            components = listOf(
                ownerLease,
                prepareDeadline,
                waiterTimeout,
                maxWaitersPerKey,
                maxWaitersPerInstance,
                datasourcePoolSize,
                idempotencyDbConcurrency,
                ownerPrepareConcurrency,
                connectionAcquireTimeout,
                statementTimeout,
                pollInitialInterval,
                pollMaxInterval,
                janitorBatchSize,
                retention,
                maxKeyBytes,
                maxBodyBytes,
                maxReplayBytes,
                maxHeaderNames,
                maxHeaderValues,
                maxHeaderValueBytes,
                maxAggregateHeaderBytes,
            ).map(Any::toString),
        )
    }

    private fun Duration.hasPositiveDuration(): Boolean = !isZero && !isNegative

    private companion object {
        const val MAX_KEY_BYTES = 255
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_HEADER_NAMES = 64
        const val MAX_HEADER_VALUES = 64
        const val MAX_HEADER_VALUE_BYTES = 16 * 1024
        const val MAX_AGGREGATE_HEADER_BYTES = 64 * 1024

        fun digest(domain: String, components: List<String>): String {
            val messageDigest = MessageDigest.getInstance("SHA-256")
            update(messageDigest, domain.toByteArray(UTF_8))
            components.forEach { update(messageDigest, it.toByteArray(UTF_8)) }
            return messageDigest.digest().joinToString(separator = "") { byte ->
                "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
            }
        }

        fun update(messageDigest: MessageDigest, bytes: ByteArray) {
            messageDigest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            messageDigest.update(bytes)
        }
    }
}
