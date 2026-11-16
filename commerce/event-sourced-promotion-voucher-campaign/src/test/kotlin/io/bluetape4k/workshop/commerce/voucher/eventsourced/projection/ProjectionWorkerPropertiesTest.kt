package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

internal class ProjectionWorkerPropertiesTest {

    companion object: KLogging()

    @Test
    fun `worker polling is enabled unless the explicit property gate disables it`() {
        ProjectionWorkerProperties().enabled.shouldBeTrue()
        ProjectionWorkerProperties(enabled = false).enabled.shouldBeFalse()
    }
}
