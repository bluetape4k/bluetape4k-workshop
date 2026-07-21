package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Fail-closed operational limits for the job safety lab. */
@ConfigurationProperties("workshop.job-safety")
data class JobSafetyProperties(
    val region: String = "region-a",
    val supportedRegions: Set<String> = setOf("region-a", "region-b"),
    val namespaceEpoch: Long = 1L,
    val timelineLimit: Int = 128,
    val defaultTimeout: Duration = Duration.ofSeconds(1),
    val fencing: Fencing = Fencing(),
    val redis: Redis = Redis(),
    val outbox: Outbox = Outbox(),
    val lab: Lab = Lab(),
) {
    init {
        region.requireNotBlank("region")
        require(supportedRegions.isNotEmpty()) { "supportedRegions must not be empty" }
        require(region in supportedRegions) { "region must be one of $supportedRegions" }
        namespaceEpoch.requirePositiveNumber("namespaceEpoch")
        require(timelineLimit in 1..MAX_TIMELINE_EVENTS) {
            "timelineLimit must be between 1 and $MAX_TIMELINE_EVENTS"
        }
        defaultTimeout.requireGt(Duration.ZERO, "defaultTimeout")
    }

    data class Fencing(
        val leaseTtl: Duration = Duration.ofSeconds(5),
        val renewInterval: Duration = Duration.ofSeconds(2),
    ) {
        init {
            leaseTtl.requireGt(Duration.ZERO, "leaseTtl")
            renewInterval.requireGt(Duration.ZERO, "renewInterval")
            require(renewInterval < leaseTtl) { "renewInterval must be shorter than leaseTtl" }
        }
    }

    data class Redis(
        val uri: String = "redis://localhost:6379",
        val commandTimeout: Duration = Duration.ofMillis(500),
    ) {
        init {
            uri.requireNotBlank("uri")
            commandTimeout.requireGt(Duration.ZERO, "commandTimeout")
        }
    }

    data class Outbox(
        val claimTimeout: Duration = Duration.ofSeconds(30),
    ) {
        init {
            claimTimeout.requireGt(Duration.ZERO, "claimTimeout")
        }
    }

    data class Lab(
        val unsafeEnabled: Boolean = false,
    )

    companion object {
        const val MAX_TIMELINE_EVENTS: Int = 512
    }
}
