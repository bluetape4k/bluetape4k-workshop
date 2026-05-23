package io.bluetape4k.workshop.leader.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGe
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * Configuration properties for the leader election workshop module.
 *
 * Bound from the `leader.*` property prefix.
 *
 * ## Behavior / Contract
 * - [leaseTime] must be greater than or equal to [waitTime]; an [IllegalArgumentException] is thrown otherwise.
 * - [redis] defaults to `redis://localhost:6379`.
 * - [jobFixedDelay] is a Spring Duration string (e.g., `"PT10S"` or `"10s"`); defaults to 10 seconds.
 */
@ConfigurationProperties(prefix = "leader")
data class LeaderElectionProperties(
    val redis: RedisConfig = RedisConfig(),
    /** How long to wait for the distributed lock before giving up. Bound as java.time.Duration. */
    val waitTime: Duration = Duration.ofSeconds(2),
    /** How long the lock is held (TTL). Bound as java.time.Duration. */
    val leaseTime: Duration = Duration.ofSeconds(30),
    /** Fixed delay between scheduled job invocations (Spring Duration string). */
    val jobFixedDelay: String = "PT10S",
) : Serializable {

    init {
        // Use Duration comparison directly to avoid toMillis() overflow for large values
        leaseTime.requireGe(waitTime, "leaseTime must be >= waitTime")
    }

    /**
     * Redis connection configuration nested inside [LeaderElectionProperties].
     */
    data class RedisConfig(
        val url: String = "redis://localhost:6379",
    ) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object : KLogging() {
        private const val serialVersionUID: Long = 1L
    }
}
