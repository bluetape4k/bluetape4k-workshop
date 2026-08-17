package io.bluetape4k.workshop.aws.kinesis

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/** stream 상태만 allowlist로 노출하고 endpoint·payload·credential은 포함하지 않습니다. */
@Component
class KinesisWorkshopHealthIndicator(
    private val properties: KinesisWorkshopProperties,
) : HealthIndicator {

    private val state = AtomicReference<KinesisStreamStatus?>(null)

    fun markReady() {
        state.set(KinesisStreamStatus.ACTIVE)
    }

    fun markFailure() {
        state.set(KinesisStreamStatus.FAILED)
    }

    override fun health(): Health {
        val status = state.get()
        return when {
            properties.profile == KinesisWorkshopProperties.LOCAL_PROFILE -> Health.up().build()
            status == null -> Health.status(Status.UNKNOWN).build()
            status == KinesisStreamStatus.FAILED -> Health.down().build()
            else -> Health.up().build()
        }
    }
}
