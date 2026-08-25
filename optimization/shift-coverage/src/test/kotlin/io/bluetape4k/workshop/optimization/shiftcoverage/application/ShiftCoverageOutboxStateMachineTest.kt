package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EffectKey
import java.time.Instant
import org.junit.jupiter.api.Test

class ShiftCoverageOutboxStateMachineTest {
    @Test
    fun `unknown delivery requires definitive lookup before operator redrive`() {
        val store = ShiftCoverageOutboxStore()
        val now = Instant.parse("2026-08-24T09:00:00Z")
        val effect = store.enqueue("request-1", EffectKey("123456789ABCDEFGHJKLMN"), now)
        val claimed = store.claim("worker-1", now).shouldNotBeNull()
        store.markStarted(claimed, "worker-1", now)
        store.markDeliveryUnknown(effect.effectKey, "worker-1", claimed.leaseToken ?: "", now)
        store.find(effect.effectKey)?.status shouldBeEqualTo ShiftCoverageOutboxStatus.DELIVERY_UNKNOWN
        assertFailsWith<ShiftCoverageOutboxRedriveRejected> { store.redrive(effect.effectKey, "operator-1", "retry") }
        store.reconcile(effect.effectKey, providerFound = false, Instant.parse("2026-08-24T09:00:01Z"))
        store.redrive(effect.effectKey, "operator-1", "provider not found")?.status shouldBeEqualTo ShiftCoverageOutboxStatus.PENDING
    }

    @Test
    fun `expired claims are reclaimed with a delivery fence`() {
        val store = ShiftCoverageOutboxStore()
        val now = Instant.parse("2026-08-24T09:00:00Z")
        val effect = EffectKey("expired-123456789ABCDE")
        store.enqueue("request-expired", effect, now)
        val claimed = store.claim("worker-expired", now).shouldNotBeNull()
        claimed.status shouldBeEqualTo ShiftCoverageOutboxStatus.CLAIMED
        store.markStarted(claimed, "worker-expired", now)

        store.recoverExpired(now.plusSeconds(31)) shouldBeEqualTo 1
        store.find(effect)?.status shouldBeEqualTo ShiftCoverageOutboxStatus.DELIVERY_UNKNOWN
        store.markDeliveryUnknown(effect, "worker-expired", claimed.leaseToken.orEmpty(), now.plusSeconds(31)).shouldBeFalse()
    }
}
