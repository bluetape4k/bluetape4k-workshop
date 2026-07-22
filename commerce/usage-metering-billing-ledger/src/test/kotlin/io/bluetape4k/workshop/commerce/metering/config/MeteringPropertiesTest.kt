package io.bluetape4k.workshop.commerce.metering.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

class MeteringPropertiesTest {

    @Test
    fun `defaults keep work bounded and retryable`() {
        val properties = MeteringProperties()

        properties.commandReceipt.lease shouldBeEqualTo Duration.ofSeconds(30)
        properties.commandReceipt.retention shouldBeEqualTo Duration.ofHours(24)
        properties.commandReceipt.terminalResponseBytes shouldBeEqualTo 16 * 1024
        properties.allowedLateness shouldBeEqualTo Duration.ofHours(48)
        properties.occurredAtRetention shouldBeEqualTo Duration.ofDays(400)
        properties.occurredAtFutureSkew shouldBeEqualTo Duration.ofMinutes(5)
        properties.close.batchSize shouldBeEqualTo 200
        properties.close.maxBatchSize shouldBeEqualTo 1_000
        properties.close.maxBatchesPerTick shouldBeEqualTo 5
        properties.close.schedulerDelay shouldBeEqualTo Duration.ofSeconds(5)
        properties.reconciliation.pageSize shouldBeEqualTo 200
        properties.reconciliation.maxPageSize shouldBeEqualTo 500
    }

    @Test
    fun `receipt limits reject unsafe durations and response sizes`() {
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(lease = Duration.ofSeconds(4))
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(lease = Duration.ofMinutes(6))
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(
                lease = Duration.ofMinutes(1),
                retention = Duration.ofSeconds(30),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(terminalResponseBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(terminalResponseBytes = 16 * 1024 + 1)
        }
    }

    @Test
    fun `close limits reject unbounded batches and invalid cadence`() {
        assertFailsWith<IllegalArgumentException> { MeteringProperties.Close(batchSize = 0) }
        assertFailsWith<IllegalArgumentException> { MeteringProperties.Close(batchSize = 1_001) }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.Close(batchSize = 201, maxBatchSize = 200)
        }
        assertFailsWith<IllegalArgumentException> { MeteringProperties.Close(maxBatchesPerTick = 0) }
        assertFailsWith<IllegalArgumentException> { MeteringProperties.Close(maxBatchesPerTick = 21) }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.Close(schedulerDelay = Duration.ZERO)
        }
    }

    @Test
    fun `reconciliation page is positive and capped`() {
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.Reconciliation(pageSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.Reconciliation(pageSize = 501)
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.Reconciliation(pageSize = 201, maxPageSize = 200)
        }
    }

    @Test
    fun `time horizons must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties(allowedLateness = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties(occurredAtRetention = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties(occurredAtFutureSkew = Duration.ofSeconds(-1))
        }
    }
}
