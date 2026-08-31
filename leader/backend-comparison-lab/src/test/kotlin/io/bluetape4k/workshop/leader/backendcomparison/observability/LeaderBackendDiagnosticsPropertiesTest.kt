package io.bluetape4k.workshop.leader.backendcomparison.observability

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.backendcomparison.service.LeaderBackendCatalog
import org.junit.jupiter.api.Test

class LeaderBackendDiagnosticsPropertiesTest {

    private val catalog = LeaderBackendCatalog()

    @Test
    fun `properties expose credential free defaults`() {
        val properties = LeaderBackendDiagnosticsProperties()

        properties.backendId shouldBeEqualTo "redis-lettuce"
        properties.probeOutcome shouldBeEqualTo ProbeOutcome.UNKNOWN
    }

    @Test
    fun `unknown backend id fails closed`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ProfiledLeaderElector(
                catalog = catalog,
                properties = LeaderBackendDiagnosticsProperties(backendId = "missing-backend"),
            )
        }

        error.message shouldBeEqualTo "Unknown leader backend id: missing-backend"
    }
}
