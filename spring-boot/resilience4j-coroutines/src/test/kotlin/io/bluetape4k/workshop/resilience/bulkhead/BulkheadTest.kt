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
 * Bulkhead — concurrent call limit 검증입니다.
 *
 * [io.github.resilience4j.bulkhead.Bulkhead] 가 concurrent in-flight call 을 올바르게 제한하고,
 * limit 을 넘으면 [BulkheadFullException] 을 throw 하는지 검증합니다.
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
 * ## 동작 / 계약
 * - `maxConcurrentCalls` 개 permit 까지만 동시에 획득할 수 있습니다.
 * - limit 을 넘는 call 은 [BulkheadFullException] 으로 거부됩니다.
 * - permit 을 release 하면 다음 caller 가 사용할 slot 이 생깁니다.
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
            // 모든 permit 을 획득합니다.
            repeat(maxCalls) {
                bulkhead.tryAcquirePermission().shouldBeTrue()
                acquired++
            }

            // bulkhead 가 가득 찼으므로 다음 acquire 는 실패해야 합니다.
            bulkhead.tryAcquirePermission().shouldBeFalse()
            log.debug { "Bulkhead correctly rejected call beyond maxConcurrentCalls=$maxCalls" }
        } finally {
            repeat(acquired) { bulkhead.releasePermission() }
        }

        // release 후 permit 을 다시 사용할 수 있습니다.
        bulkhead.tryAcquirePermission().shouldBeTrue()
        bulkhead.releasePermission()
    }

    @Test
    fun `backendB bulkhead allows up to maxConcurrentCalls permits`() {
        val bulkhead = bulkheadRegistry.bulkhead(BACKEND_B)
        val maxCalls = bulkhead.bulkheadConfig.maxConcurrentCalls

        maxCalls shouldBeEqualTo MAX_CONCURRENT_CALLS_BACKEND_B

        // 현재 사용 가능한 slot 을 snapshot 합니다. 다른 test 가 일부 permit 을 획득했을 수 있습니다.
        // test 가 idempotent 하도록 남아 있는 slot 만 채웁니다.
        val available = bulkhead.metrics.availableConcurrentCalls
        log.debug { "backendB initial available=$available maxConcurrentCalls=$maxCalls" }

        var acquired = 0
        try {
            repeat(available) {
                bulkhead.tryAcquirePermission().shouldBeTrue()
                acquired++
            }

            // 이제 bulkhead 가 가득 찼으므로 다음 acquire 는 실패해야 합니다.
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

        // bulkhead 를 포화시킵니다. finally 에서 과도하게 release 하지 않도록 실제 acquire 를 추적합니다.
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

        // permit 하나를 acquire 하고 성공을 확인한 뒤 metrics update 를 검증합니다.
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
