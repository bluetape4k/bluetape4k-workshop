package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

@Tag("integration")
class MeteringRepositoryContractTest {

    @Test
    fun `fixture seeds authority through Exposed and tenant meter uniqueness is enforced`() {
        MeteringDatabaseFixture().use { fixture ->
            val seed = fixture.resetAndSeed()

            fixture.executor.transaction {
                MeterEntity.findById(seed.meterId)?.meterCode shouldBeEqualTo seed.meterCode
                BillingCalendarEntity.findById(seed.calendarId)?.currency shouldBeEqualTo seed.currency
            }

            assertFailsWith<ExposedSQLException> {
                fixture.executor.transaction {
                    MeterEntity.new {
                        tenantId = seed.tenantId
                        meterCode = seed.meterCode
                        unit = "duplicate"
                        description = null
                        active = true
                        createdAt = Instant.parse("2026-07-01T00:00:01Z")
                    }
                }
            }
        }
    }
}
