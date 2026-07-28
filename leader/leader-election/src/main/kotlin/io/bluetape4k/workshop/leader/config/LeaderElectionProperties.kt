package io.bluetape4k.workshop.leader.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGe
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * leader election 워크숍 모듈의 configuration property입니다.
 *
 * `leader.*` property prefix에서 바인딩됩니다.
 *
 * ## 동작 / 계약
 * - [leaseTime]은 [waitTime]보다 크거나 같아야 하며, 그렇지 않으면 [IllegalArgumentException]을 던집니다.
 * - [redis] 기본값은 `redis://localhost:6379`입니다.
 * - [jobFixedDelay]는 `"PT10S"` 또는 `"10s"` 같은 Spring Duration 문자열이며, 기본값은 10초입니다.
 */
@ConfigurationProperties(prefix = "leader")
data class LeaderElectionProperties(
    val redis: RedisConfig = RedisConfig(),
    /** distributed lock을 포기하기 전에 기다릴 시간입니다. java.time.Duration으로 바인딩됩니다. */
    val waitTime: Duration = Duration.ofSeconds(2),
    /** lock을 보유하는 시간(TTL)입니다. java.time.Duration으로 바인딩됩니다. */
    val leaseTime: Duration = Duration.ofSeconds(30),
    /** scheduled job 호출 사이의 fixed delay입니다(Spring Duration 문자열). */
    val jobFixedDelay: String = "PT10S",
) : Serializable {

    init {
        // 큰 값에서 toMillis() overflow를 피하려고 Duration 비교를 직접 사용합니다.
        leaseTime.requireGe(waitTime, "leaseTime must be >= waitTime")
    }

    /**
     * [LeaderElectionProperties] 안에 중첩된 Redis connection configuration입니다.
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
