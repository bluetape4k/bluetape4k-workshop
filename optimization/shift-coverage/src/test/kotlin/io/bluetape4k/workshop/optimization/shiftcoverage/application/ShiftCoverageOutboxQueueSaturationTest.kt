package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EffectKey
import org.junit.jupiter.api.Test
import java.time.Instant

class ShiftCoverageOutboxQueueSaturationTest {

    companion object: KLogging()

    @Test
    fun `full delivery queue leaves outbox without claim or write`() {
        val store = ShiftCoverageOutboxStore()
        val queue = ShiftCoverageDeliveryQueue(capacity = 8)

        repeat(8) {
            queue.offer(EffectKey("q${it.toString().padStart(21, '0')}"))
        }

        val effect = EffectKey("e${"0".repeat(21)}")
        val worker = ShiftCoverageOutboxWorker(store, queue)

        worker.admit(effect, "request-1").shouldBeFalse()
        store.find(effect).shouldBeNull()
        queue.size() shouldBeEqualTo 8
        store.claim("worker", Instant.now()).shouldBeNull()
    }
}
