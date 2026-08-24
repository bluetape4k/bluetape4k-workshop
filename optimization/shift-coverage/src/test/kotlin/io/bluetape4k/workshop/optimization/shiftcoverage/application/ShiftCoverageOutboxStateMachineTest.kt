package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EffectKey
import java.time.Instant
import org.junit.jupiter.api.Test

class ShiftCoverageOutboxStateMachineTest {
    @Test
    fun `unknown delivery requires definitive lookup before operator redrive`() {
        val store = ShiftCoverageOutboxStore()
        val now = Instant.parse("2026-08-24T09:00:00Z")
        val effect = store.enqueue("request-1", EffectKey("123456789ABCDEFGHJKLMN"), now)
        val claimed = checkNotNull(store.claim("worker-1", now))
        store.markStarted(claimed, "worker-1")
        store.markDeliveryUnknown(effect.effectKey, "worker-1", claimed.leaseToken ?: "")
        store.find(effect.effectKey)?.status shouldBeEqualTo ShiftCoverageOutboxStatus.DELIVERY_UNKNOWN
        assertFailsWith<ShiftCoverageOutboxRedriveRejected> { store.redrive(effect.effectKey, "operator-1", "retry") }
        store.reconcile(effect.effectKey, providerFound = false, Instant.parse("2026-08-24T09:00:01Z"))
        store.redrive(effect.effectKey, "operator-1", "provider not found")?.status shouldBeEqualTo ShiftCoverageOutboxStatus.PENDING
    }
}
