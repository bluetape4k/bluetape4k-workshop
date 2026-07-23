package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import org.springframework.boot.context.properties.ConfigurationProperties

/** Disabling this gate preserves projection tables and the active generation; it only stops new polling work. */
@ConfigurationProperties("voucher.projection.worker")
internal data class ProjectionWorkerProperties(
    val enabled: Boolean = true,
)

internal class ProjectionWorkerGate(
    private val properties: ProjectionWorkerProperties,
) {
    fun start(
        loop: ProjectionPollingLoop,
        key: ProjectionKey,
        ownerDigest: String,
    ): ProjectionLoopHandle? =
        if (properties.enabled) loop.start(key, ownerDigest) else null
}
