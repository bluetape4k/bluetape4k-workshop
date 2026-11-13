package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** job safety lab의 fail-closed 운영 한계값입니다. */
@ConfigurationProperties("workshop.job-safety")
data class JobSafetyProperties(
    val region: String = "region-a",
    val supportedRegions: Set<String> = setOf("region-a", "region-b"),
    val namespaceEpoch: Long = 1L,
    val timelineLimit: Int = 128,
    val defaultTimeout: Duration = 1.seconds(),
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
        val leaseTtl: Duration = 5.seconds(),
        val renewInterval: Duration = 2.seconds(),
    ) {
        init {
            leaseTtl.requirePositiveDuration("leaseTtl")
            renewInterval.requirePositiveDuration("renewInterval")
            require(renewInterval < leaseTtl) { "renewInterval must be shorter than leaseTtl" }
        }
    }

    data class Redis(
        val uri: String = "redis://localhost:6379",
        val commandTimeout: Duration = 500.millis(),
    ) {
        init {
            uri.requireNotBlank("uri")
            commandTimeout.requireGt(Duration.ZERO, "commandTimeout")
        }
    }

    data class Outbox(
        val claimTimeout: Duration = 30.seconds(),
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
