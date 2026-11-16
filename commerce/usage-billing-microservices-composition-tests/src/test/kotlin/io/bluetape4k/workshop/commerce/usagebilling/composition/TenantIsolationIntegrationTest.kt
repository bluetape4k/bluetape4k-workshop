package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.composition.fixture.UsageBillingMicroserviceFixture
import org.junit.jupiter.api.Test

class TenantIsolationIntegrationTest {

    companion object: KLogging()

    @Test
    fun `Query tenant authority cannot read another tenant projection`() {
        UsageBillingMicroserviceFixture().use { fixture ->
            fixture.start()

            fixture.queryTenantAccessAllowed("tenant-a", "tenant-a").shouldBeTrue()
            fixture.queryTenantAccessAllowed("tenant-a", "tenant-b").shouldBeFalse()
        }
    }
}
