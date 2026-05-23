package io.bluetape4k.workshop.resilience.bulkhead

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.resilience.AbstractResilienceTest
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.bulkhead.BulkheadRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Bulkhead — concurrent call limit verification.
 *
 * Verifies that [io.github.resilience4j.bulkhead.Bulkhead] correctly limits concurrent
 * in-flight calls and throws [BulkheadFullException] when the limit is exceeded.
 *
 * ## Configuration (application.yml)
 * ```yaml
 * resilience4j.bulkhead:
 *   instances:
 *     backendA:
 *       maxConcurrentCalls: 10
 *     backendB:
 *       maxConcurrentCalls: 20
 *       maxWaitDuration: 10ms
 * ```
 *
 * ## Behavior / Contract
 * - Up to `maxConcurrentCalls` permits can be acquired simultaneously.
 * - Any call beyond the limit is rejected with [BulkheadFullException].
 * - Releasing a permit makes a slot available for the next caller.
 */
class BulkheadTest : AbstractResilienceTest() {

    companion object : KLoggingChannel() {
        private const val MAX_CONCURRENT_CALLS_BACKEND_A = 10
        private const val MAX_CONCURRENT_CALLS_BACKEND_B = 20
    }

    @Autowired
    private val bulkheadRegistry: BulkheadRegistry = uninitialized()

    @Test
    fun `backendA bulkhead allows up to maxConcurrentCalls permits`() {
        val bulkhead = bulkheadRegistry.bulkhead(BACKEND_A)
        val maxCalls = bulkhead.bulkheadConfig.maxConcurrentCalls

        maxCalls shouldBeEqualTo MAX_CONCURRENT_CALLS_BACKEND_A
        log.debug { "backendA bulkhead maxConcurrentCalls=$maxCalls" }

        var acquired = 0
        try {
            // Acquire all permits
            repeat(maxCalls) {
                bulkhead.tryAcquirePermission().shouldBeTrue()
                acquired++
            }

            // Next acquire must fail — bulkhead is full
            bulkhead.tryAcquirePermission().shouldBeFalse()
            log.debug { "Bulkhead correctly rejected call beyond maxConcurrentCalls=$maxCalls" }
        } finally {
            repeat(acquired) { bulkhead.releasePermission() }
        }

        // After release, permit is available again
        bulkhead.tryAcquirePermission().shouldBeTrue()
        bulkhead.releasePermission()
    }

    @Test
    fun `backendB bulkhead allows up to maxConcurrentCalls permits`() {
        val bulkhead = bulkheadRegistry.bulkhead(BACKEND_B)
        val maxCalls = bulkhead.bulkheadConfig.maxConcurrentCalls

        maxCalls shouldBeEqualTo MAX_CONCURRENT_CALLS_BACKEND_B

        // Snapshot currently available slots — other tests may have acquired some permits.
        // We saturate only the remaining available slots so the test is idempotent.
        val available = bulkhead.metrics.availableConcurrentCalls
        log.debug { "backendB initial available=$available maxConcurrentCalls=$maxCalls" }

        var acquired = 0
        try {
            repeat(available) {
                bulkhead.tryAcquirePermission().shouldBeTrue()
                acquired++
            }

            // Bulkhead is now full — next acquire must fail
            bulkhead.tryAcquirePermission().shouldBeFalse()
            log.debug { "Bulkhead correctly rejected call beyond maxConcurrentCalls=$maxCalls" }
        } finally {
            repeat(acquired) { bulkhead.releasePermission() }
        }
    }

    @Test
    fun `executing beyond bulkhead limit throws BulkheadFullException`() {
        val bulkhead = bulkheadRegistry.bulkhead(BACKEND_A)
        val available = bulkhead.metrics.availableConcurrentCalls
        log.debug { "backendA initial available=$available" }

        // Saturate the bulkhead — track actual acquisitions to avoid over-releasing in finally
        var acquired = 0
        var exceptionThrown = false
        try {
            repeat(available) {
                if (bulkhead.tryAcquirePermission()) acquired++
            }

            bulkhead.executeCallable {
                "this should not execute when bulkhead is full"
            }
        } catch (e: BulkheadFullException) {
            exceptionThrown = true
            log.debug { "BulkheadFullException correctly thrown: ${e.message}" }
        } finally {
            repeat(acquired) { bulkhead.releasePermission() }
        }

        exceptionThrown.shouldBeTrue()
    }

    @Test
    fun `bulkhead metrics reflect acquired and available calls`() {
        val bulkhead = bulkheadRegistry.bulkhead(BACKEND_A)
        val initialAvailable = bulkhead.metrics.availableConcurrentCalls

        initialAvailable shouldBeGreaterOrEqualTo 0

        // Acquire one permit — assert it succeeds, then verify metrics update
        val acquired = bulkhead.tryAcquirePermission()
        acquired.shouldBeTrue()
        try {
            val availableAfterAcquire = bulkhead.metrics.availableConcurrentCalls
            availableAfterAcquire shouldBeEqualTo (initialAvailable - 1)
            log.debug { "Available after acquire: $availableAfterAcquire (was $initialAvailable)" }
        } finally {
            bulkhead.releasePermission()
        }
    }
}
