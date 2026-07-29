package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import org.springframework.boot.context.properties.ConfigurationProperties

/** 이 gate를 비활성화해도 projection table과 active generation은 보존됩니다. 새 polling work만 중지합니다. */
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
