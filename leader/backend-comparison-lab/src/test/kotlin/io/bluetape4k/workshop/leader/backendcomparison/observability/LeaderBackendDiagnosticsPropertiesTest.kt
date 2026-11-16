package io.bluetape4k.workshop.leader.backendcomparison.observability

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.leader.backendcomparison.service.LeaderBackendCatalog
import org.junit.jupiter.api.Test

class LeaderBackendDiagnosticsPropertiesTest {

    companion object: KLogging()

    private val catalog = LeaderBackendCatalog()

    @Test
    fun `properties expose credential free defaults`() {
        val properties = LeaderBackendDiagnosticsProperties()

        log.debug { "properties=$properties" }
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
        log.debug { "error=$error" }
        error.message shouldBeEqualTo "Unknown leader backend id: missing-backend"
    }
}
