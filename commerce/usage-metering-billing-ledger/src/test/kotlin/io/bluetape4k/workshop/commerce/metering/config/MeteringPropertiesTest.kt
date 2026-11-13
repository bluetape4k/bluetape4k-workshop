package io.bluetape4k.workshop.commerce.metering.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Duration

class MeteringPropertiesTest {

    companion object: KLogging()

    @Test
    fun `defaults keep work bounded and retryable`() {
        val properties = MeteringProperties()

        log.debug { "properties=$properties" }
        properties.commandReceipt.lease shouldBeEqualTo 30.seconds()
        properties.commandReceipt.retention shouldBeEqualTo 24.hours()
        properties.commandReceipt.terminalResponseBytes shouldBeEqualTo 16 * 1024
        properties.allowedLateness shouldBeEqualTo 48.hours()
        properties.occurredAtRetention shouldBeEqualTo 400.hours()
        properties.occurredAtFutureSkew shouldBeEqualTo 5.minutes()
        properties.close.batchSize shouldBeEqualTo 200
        properties.close.maxBatchSize shouldBeEqualTo 1_000
        properties.close.maxBatchesPerTick shouldBeEqualTo 5
        properties.close.schedulerDelay shouldBeEqualTo 5.seconds()
        properties.reconciliation.pageSize shouldBeEqualTo 200
        properties.reconciliation.maxPageSize shouldBeEqualTo 500
    }

    @Test
    fun `receipt limits reject unsafe durations and response sizes`() {
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(lease = 4.seconds())
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(lease = 6.seconds())
        }
        assertFailsWith<IllegalArgumentException> {
            MeteringProperties.CommandReceipt(
                lease = 1.minutes(),
                retention = 30.seconds(),
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
            MeteringProperties(occurredAtFutureSkew = (-1).seconds())
        }
    }
}
